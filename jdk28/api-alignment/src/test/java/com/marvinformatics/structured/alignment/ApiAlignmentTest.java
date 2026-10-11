package com.marvinformatics.structured.alignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ApiAlignmentTest {

  private static final String BACKPORT = "com.marvinformatics.structured.";
  private static final String JDK = "java.util.concurrent.";

  static Stream<String> publicTypes() {
    return Stream.of(
        "StructuredTaskScope",
        "StructuredTaskScope$Subtask",
        "StructuredTaskScope$Subtask$State",
        "StructuredTaskScope$Joiner",
        "StructuredTaskScope$Configuration",
        "StructuredTaskScope$CancelledByTimeoutException",
        "StructureViolationException");
  }

  @ParameterizedTest
  @MethodSource("publicTypes")
  void backportTypeMatchesJdkType(String simpleName) throws ClassNotFoundException {
    Class<?> jdk = Class.forName(JDK + simpleName);
    Class<?> backport = Class.forName(BACKPORT + simpleName);

    assertThat(describe(backport)).containsExactlyElementsOf(describe(jdk));
  }

  @ParameterizedTest
  @MethodSource("publicTypes")
  void everyPublicNestedTypeIsCovered(String simpleName) throws ClassNotFoundException {
    List<String> covered = publicTypes().toList();

    Stream.of(Class.forName(JDK + simpleName).getDeclaredClasses())
        .filter(nested -> Modifier.isPublic(nested.getModifiers()))
        .map(nested -> nested.getName().substring(JDK.length()))
        .forEach(nested -> assertThat(covered).contains(nested));
  }

  private static List<String> describe(Class<?> type) {
    List<String> description = new ArrayList<>();
    description.add("modifiers " + Modifier.toString(type.getModifiers() & ~Modifier.STRICT));
    description.add("kind " + (type.isEnum() ? "enum" : type.isInterface() ? "interface" : "class")
        + (type.isSealed() ? " sealed" : ""));
    description.add("type parameters " + typeParameters(type.getTypeParameters()));
    description.add("superclass " + normalize(type.getGenericSuperclass()));
    Stream.of(type.getGenericInterfaces())
        .map(it -> "implements " + normalize(it))
        .sorted()
        .forEach(description::add);
    Stream.of(type.getDeclaredConstructors())
        .filter(ApiAlignmentTest::isApi)
        .map(Constructor::toGenericString)
        .map(ApiAlignmentTest::normalize)
        .sorted()
        .forEach(description::add);
    Stream.of(type.getDeclaredMethods())
        .filter(ApiAlignmentTest::isApi)
        .map(Method::toGenericString)
        .map(ApiAlignmentTest::normalize)
        .sorted()
        .forEach(description::add);
    Stream.of(type.getDeclaredFields())
        .filter(field -> Modifier.isPublic(field.getModifiers()))
        .map(Field::toGenericString)
        .map(ApiAlignmentTest::normalize)
        .sorted()
        .forEach(description::add);
    Stream.of(type.getDeclaredClasses())
        .filter(nested -> Modifier.isPublic(nested.getModifiers()))
        .map(nested -> "nested " + normalize(nested.getName()))
        .sorted()
        .forEach(description::add);
    if (Serializable.class.isAssignableFrom(type)) {
      description.add("serialVersionUID " + ObjectStreamClass.lookup(type).getSerialVersionUID());
    }
    return description;
  }

  private static boolean isApi(Executable executable) {
    return !executable.isSynthetic()
        && (Modifier.isPublic(executable.getModifiers())
            || executable.getDeclaringClass().isInterface()
                && !Modifier.isPrivate(executable.getModifiers()));
  }

  private static String typeParameters(TypeVariable<?>[] variables) {
    return Arrays.stream(variables)
        .map(variable -> variable.getName() + " extends " + Arrays.stream(variable.getBounds())
            .map(ApiAlignmentTest::normalize)
            .toList())
        .toList()
        .toString();
  }

  private static String normalize(Type type) {
    return type == null ? "none" : normalize(type.getTypeName());
  }

  private static String normalize(String signature) {
    return signature.replace(BACKPORT, JDK);
  }
}
