#!/bin/bash
#
# Run the JDK 28 verification flows in Docker, so no local JDK is needed.

set -euo pipefail

script_path="$(readlink -f "${BASH_SOURCE[0]}")"
PROJECT_ROOT="${script_path%/*/*}"
readonly PROJECT_ROOT
dockerfile_jdk28_image="$(sed -n 's/^FROM //p' "${PROJECT_ROOT}/jdk28/Dockerfile")"
pom_openjdk_ref="$(sed -n 's:.*<openjdk.ref>\(.*\)</openjdk.ref>.*:\1:p' "${PROJECT_ROOT}/pom.xml")"
readonly POM_OPENJDK_REF="${pom_openjdk_ref}"
readonly JDK25_IMAGE="${JDK25_IMAGE:-eclipse-temurin:25-jdk}"
readonly JDK28_IMAGE="${JDK28_IMAGE:-${dockerfile_jdk28_image}}"
readonly MAVEN_REPO="${MAVEN_REPO:-${HOME}/.m2}"
readonly MODULES_EXCLUDED_ON_JDK28='!structured-concurrency-rewrite'

print_usage() {
  cat <<EOF
Usage: $(basename "$0") [--skip-install] [--] [MAVEN_ARG...]

Build on JDK 25, rewrite the backport tests to java.util.concurrent with
OpenRewrite (still on JDK 25), then run API alignment, the bytecode transform
tests and the source rewrite tests on JDK 28. Extra arguments go to every
Maven invocation.

Options:
  --skip-install  reuse artifacts already installed in the Maven repository
  -h, --help      show this help

Environment:
  JDK25_IMAGE  image for the JDK 25 stages (default: ${JDK25_IMAGE})
  JDK28_IMAGE  image for the JDK 28 stage (default: FROM in jdk28/Dockerfile)
  MAVEN_REPO   Maven home mounted into containers (default: ${MAVEN_REPO})

Examples:
  $(basename "$0")
  $(basename "$0") --skip-install -o
EOF
}

log_progress() {
  echo "==> $*" >&2
}

run_mvnw_in_container() {
  local image="$1"
  shift
  # OpenRewrite skips git-ignored files, and the rewritten sources are git-ignored.
  local -a git_hiding_docker_opts=(--mount "type=tmpfs,destination=/work/.git")
  if [[ -f "${PROJECT_ROOT}/.git" ]]; then
    git_hiding_docker_opts=(-v /dev/null:/work/.git:ro)
  fi
  docker run --rm \
    -u "$(id -u):$(id -g)" \
    -e HOME=/tmp/home \
    -e MAVEN_OPTS=-Duser.home=/tmp/home \
    -v "${MAVEN_REPO}:/tmp/home/.m2" \
    -v "${PROJECT_ROOT}:/work" \
    "${git_hiding_docker_opts[@]}" \
    -w /work \
    "${image}" \
    ./mvnw -B "$@"
}

require_image_matches_openjdk_ref() {
  local java_version
  java_version="$(docker run --rm "${JDK28_IMAGE}" sh -c 'echo "${JAVA_VERSION}"')"
  local image_openjdk_ref="jdk-${java_version/-ea/}"
  if [[ "${image_openjdk_ref}" != "${POM_OPENJDK_REF}" ]]; then
    echo "$(basename "$0"): ${JDK28_IMAGE} is ${image_openjdk_ref}," \
      "but openjdk.ref in pom.xml is ${POM_OPENJDK_REF}" >&2
    exit 1
  fi
}

main() {
  local skip_install='false'
  local -a maven_args=()

  while (( $# > 0 )); do
    case "$1" in
      -h | --help)
        print_usage
        exit 0
        ;;
      --skip-install) skip_install='true' ;;
      --)
        shift
        maven_args+=("$@")
        break
        ;;
      *) maven_args+=("$1") ;;
    esac
    shift
  done

  if ! command -v docker >/dev/null; then
    echo "$(basename "$0"): docker is required but was not found on PATH" >&2
    exit 1
  fi

  require_image_matches_openjdk_ref

  # Created up front so Docker does not create it owned by root.
  mkdir -p "${MAVEN_REPO}"

  if [[ "${skip_install}" == 'false' ]]; then
    log_progress "[1/3] Building and installing on ${JDK25_IMAGE}"
    run_mvnw_in_container "${JDK25_IMAGE}" install "${maven_args[@]}"
  fi

  log_progress "[2/3] Rewriting backport tests with OpenRewrite on ${JDK25_IMAGE}"
  run_mvnw_in_container "${JDK25_IMAGE}" -Pjdk28 -pl jdk28/source-rewrite-tests \
    -Denforcer.skip clean process-test-sources "${maven_args[@]}"

  log_progress "[3/3] Verifying against the JDK on ${JDK28_IMAGE}"
  run_mvnw_in_container "${JDK28_IMAGE}" -Pjdk28 -Drewrite.skip \
    -pl "${MODULES_EXCLUDED_ON_JDK28}" verify "${maven_args[@]}"

  log_progress "All JDK 28 flows passed"
}

main "$@"
