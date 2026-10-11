package com.marvinformatics.structured.alignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ApiAlignmentTest {

  private static final String BACKPORT_PACKAGE_PREFIX = "com.marvinformatics.structured.";
  private static final String JDK_PACKAGE_PREFIX = "java.util.concurrent.";
  private static final List<String> TOP_LEVEL_TYPE_NAMES =
      List.of("StructureViolationException", "StructuredTaskScope");

  static Stream<String> streamPublicApiTypeNames() throws ClassNotFoundException {
    List<Class<?>> topLevelTypes = new ArrayList<>();
    for (String name : TOP_LEVEL_TYPE_NAMES) {
      topLevelTypes.add(Class.forName(JDK_PACKAGE_PREFIX + name));
    }
    return topLevelTypes.stream()
        .flatMap(ApiAlignmentTest::withPublicNestedTypes)
        .map(type -> type.getName().substring(JDK_PACKAGE_PREFIX.length()));
  }

  private static Stream<Class<?>> withPublicNestedTypes(Class<?> type) {
    return Stream.concat(Stream.of(type), publicNonSynthetic(type.getDeclaredClasses())
        .flatMap(ApiAlignmentTest::withPublicNestedTypes));
  }

  @Test
  void everyStructuredConcurrencyTypeInTheJdkPackageIsCompared() throws IOException {
    Path jdkPackage = FileSystems.getFileSystem(URI.create("jrt:/"))
        .getPath("modules", "java.base", "java", "util", "concurrent");
    try (Stream<Path> classFiles = Files.list(jdkPackage)) {
      List<String> structureTypeNames = classFiles
          .map(path -> path.getFileName().toString())
          .filter(fileName -> fileName.startsWith("Structure") && !fileName.contains("$"))
          .map(fileName -> fileName.substring(0, fileName.length() - ".class".length()))
          .filter(ApiAlignmentTest::isPublicJdkType)
          .sorted()
          .toList();

      assertThat(structureTypeNames).containsExactlyElementsOf(TOP_LEVEL_TYPE_NAMES);
    }
  }

  private static boolean isPublicJdkType(String name) {
    try {
      return Modifier.isPublic(Class.forName(JDK_PACKAGE_PREFIX + name).getModifiers());
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  @ParameterizedTest
  @MethodSource("streamPublicApiTypeNames")
  void backportPublicApiMatchesJdkPublicApi(String packageRelativeBinaryName)
      throws ClassNotFoundException {
    Class<?> jdkType = Class.forName(JDK_PACKAGE_PREFIX + packageRelativeBinaryName);
    Class<?> backportType = Class.forName(BACKPORT_PACKAGE_PREFIX + packageRelativeBinaryName);

    assertThat(listApiSignatures(backportType))
        .containsExactlyElementsOf(listApiSignatures(jdkType));
  }

  private static List<String> listApiSignatures(Class<?> type) {
    List<String> apiSignatures = new ArrayList<>();
    apiSignatures.add("flags " + type.accessFlags() + (type.isSealed() ? " sealed" : ""));
    apiSignatures.add("type parameters "
        + Stream.of(type.getTypeParameters()).map(ApiAlignmentTest::describeTypeParameter).toList());
    Type superclass = type.getGenericSuperclass();
    apiSignatures.add("superclass " + (superclass == null ? "none" : toJdkTypeName(superclass)));
    apiSignatures.addAll(renderSorted(
        Stream.of(type.getGenericInterfaces()), it -> "implements " + it.getTypeName()));
    apiSignatures.addAll(renderSorted(
        publicNonSynthetic(type.getDeclaredConstructors()), it -> it.toGenericString()));
    apiSignatures.addAll(renderSorted(
        publicNonSynthetic(type.getDeclaredMethods()), it -> it.toGenericString()));
    apiSignatures.addAll(renderSorted(
        publicNonSynthetic(type.getDeclaredFields()), it -> it.toGenericString()));
    apiSignatures.addAll(renderSorted(
        publicNonSynthetic(type.getDeclaredClasses()), it -> "nested " + it.getName()));
    if (Serializable.class.isAssignableFrom(type)) {
      apiSignatures.add("serialVersionUID " + ObjectStreamClass.lookup(type).getSerialVersionUID());
    }
    return apiSignatures.stream().map(ApiAlignmentTest::replaceBackportPackageWithJdkPackage).toList();
  }

  @SafeVarargs
  private static <T> Stream<T> publicNonSynthetic(T... membersOrTypes) {
    return Stream.of(membersOrTypes).filter(it -> it instanceof Member member
        ? Modifier.isPublic(member.getModifiers()) && !member.isSynthetic()
        : Modifier.isPublic(((Class<?>) it).getModifiers()));
  }

  private static <T> List<String> renderSorted(Stream<T> items, Function<T, String> render) {
    return items.map(render).map(ApiAlignmentTest::replaceBackportPackageWithJdkPackage).sorted().toList();
  }

  private static String describeTypeParameter(TypeVariable<?> typeVariable) {
    return typeVariable.getName() + " extends "
        + Stream.of(typeVariable.getBounds()).map(ApiAlignmentTest::toJdkTypeName).toList();
  }

  private static String toJdkTypeName(Type type) {
    return replaceBackportPackageWithJdkPackage(type.getTypeName());
  }

  private static String replaceBackportPackageWithJdkPackage(String reflectedText) {
    return reflectedText.replace(BACKPORT_PACKAGE_PREFIX, JDK_PACKAGE_PREFIX);
  }
}
