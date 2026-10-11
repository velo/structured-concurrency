# AGENTS.md

Guidance for coding agents working on this repository. Read [README.md](README.md) first for what
the project is and how the API was derived.

## Ground rules

The README sections "How this project started" and "Keeping the license clean" explain why these
rules exist.

- **Never copy OpenJDK implementation code.** OpenJDK is GPLv2 and this project is Apache 2.0.
  Derive behavior from the public javadoc and JEP 543, and from what the upstream tests assert. Do
  not read or paste `StructuredTaskScopeImpl.java` or `Joiners.java` from `openjdk/jdk`.
- **Never commit upstream tests.** They are downloaded at build time into
  `upstream-conformance/target/`, a module that publishes nothing. Keep them out of every other
  module.
- The public API must stay identical to `java.util.concurrent.StructuredTaskScope` at the pinned
  `openjdk.ref`. `jdk28/api-alignment` enforces this; don't add public members it would reject.
- Tests that are migrated to the JDK (`structured-concurrency/src/test/java/.../spec`) may only use
  the public API. No `StructuredTaskScopeImpl`, no `toString()` formats, no stack frame names.
- Keep test classes out of the `com.marvinformatics.structured` package itself. The migration tools
  rename that package to `java.util.concurrent`, where the JVM cannot load application classes.

## Building and testing

| Task | Command |
|---|---|
| Normal build (JDK 21 or later) | `./mvnw verify` |
| Without network for upstream tests | `./mvnw verify -Dupstream.skip` |
| Full JDK 28 verification (Docker) | `scripts/verify-jdk28.sh` |
| Only the API alignment test | `scripts/verify-jdk28.sh --skip-install`, or run `./mvnw -Pjdk28 -pl jdk28/api-alignment verify` inside the JDK 28 image |

- Every module outside `jdk28/` compiles at release 21. Upstream tests that use newer syntax are
  adapted with `replaceregexp` in `upstream-conformance/pom.xml`, never committed.
- Use the Maven wrapper. Use Docker for JDK 28; don't assume it is installed.
- Use absolute paths or `-f`/`-pl`; don't change the working directory.
- `jdk28/source-rewrite-tests` writes generated sources into its own `src/test/java`. That directory
  is gitignored and wiped by `mvn clean`. OpenRewrite skips git-ignored files, so the script hides
  `.git` from its containers. On JDK 28 the backport is not on that module's classpath, so any
  backport reference the rewrite missed fails compilation.
- OpenRewrite cannot run on JDK 28 yet, so the rewrite stage runs on JDK 25 (see the script).

## Updating to a newer OpenJDK build

1. Bump `openjdk.ref` and `jdk28.image` in `pom.xml`; the script reads the image from there.
   Renovate normally does this.
2. Run `./mvnw verify` and `scripts/verify-jdk28.sh`.
3. If the alignment test fails, change the public API to match and implement the new behavior from
   the javadoc. The alignment test finds the types to compare by itself: every public type in
   `java.util.concurrent` whose name starts with `Structure`, and all their public nested types. If
   a new type appears there, it fails until the backport has it. If a conformance test fails, fix the implementation. Only exclude an upstream test
   when it depends on JDK internals the library cannot reach, and list it under "Known
   differences" in the README.

## Code style

- Two-space indentation, as in the existing sources. Use imports, never fully qualified class
  names.
- Keep comments rare: name things so the code explains itself, and put rationale in the PR
  description. Never raise the comment-to-code ratio of a file.
- Assertions must be exact. No `containsAnyOf`, `satisfiesAnyOf` or similar.
- End every file with a newline, and leave no trailing whitespace.
- Declare every plugin and dependency version as a `<name>.version` property in the parent
  `pom.xml`, and reference it from `pluginManagement` or `dependencyManagement`. Child poms carry
  no versions except `${project.version}` for this project's own modules.

## Releasing

1. On `main`, set every module to `X.Y.Z` with
   `./mvnw versions:set -DnewVersion=X.Y.Z -DprocessAllModules=true -DgenerateBackupPoms=false`
   and commit as `Release X.Y.Z`.
2. Set every module to the next `X.Y.(Z+1)-SNAPSHOT` the same way and commit as
   `Prepare next development iteration X.Y.(Z+1)-SNAPSHOT`.
3. Push `main`, then create a GitHub Release with tag `vX.Y.Z` on the `Release X.Y.Z` commit:
   `gh release create vX.Y.Z --target <release commit> --generate-notes`.
4. The `release: created` event runs `release.yml`. It checks out the tag, sets the version from
   the tag name, and runs `./mvnw clean deploy -Prelease`. That profile attaches sources and
   javadoc, signs with GPG, and publishes the parent, `structured-concurrency`,
   `structured-concurrency-rewrite` and `structured-concurrency-transformer-rules` through the
   Central Publishing Portal. `build.yml` builds the same tag separately.
5. Only modules that declare `central-publishing-maven-plugin` in their own `release` profile are
   published. Don't add it to modules that attach no artifact, such as `upstream-conformance`.

Nothing deploys a SNAPSHOT. The release needs the repository secrets `GPG_PRIVATE_KEY` and
`MAVEN_SETTINGS_SERVER_CENTRAL` (a `<server>` element with id `central`).

## Git

- Commit only when explicitly asked. Never commit to `main` directly; check the branch first.
- Commit messages are a single line, signed off and signed: `git commit -s -S -m "..."`, with no
  co-author trailers.
