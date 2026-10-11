#!/bin/bash
#
# Run the JDK 28 verification flows in Docker, so no local JDK is needed.

set -euo pipefail

script_path="$(readlink -f "${BASH_SOURCE[0]}")"
PROJECT_ROOT="${script_path%/*/*}"
readonly PROJECT_ROOT
readonly JDK25_IMAGE="${JDK25_IMAGE:-eclipse-temurin:25-jdk}"
readonly JDK28_IMAGE="${JDK28_IMAGE:-openjdk:28-ea-18-jdk}"
readonly MAVEN_REPO="${MAVEN_REPO:-${HOME}/.m2}"
readonly JDK28_MODULES="structured-concurrency,upstream-conformance,jdk28/api-alignment,\
jdk28/transform-flow,jdk28/rewrite-flow"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--skip-install] [--] [MAVEN_ARG...]

Build on JDK 25, rewrite the backport tests to java.util.concurrent with
OpenRewrite (still on JDK 25), then run API alignment, the bytecode transform
flow and the source rewrite flow on JDK 28. Extra arguments go to every
Maven invocation.

Options:
  --skip-install  reuse artifacts already installed in the Maven repository
  -h, --help      show this help

Environment:
  JDK25_IMAGE  image for the JDK 25 stages (default: ${JDK25_IMAGE})
  JDK28_IMAGE  image for the JDK 28 stage (default: ${JDK28_IMAGE})
  MAVEN_REPO   Maven home mounted into containers (default: ${MAVEN_REPO})

Examples:
  $(basename "$0")
  $(basename "$0") --skip-install -o
EOF
}

stage() {
  echo "==> $*" >&2
}

#######################################
# Run the Maven wrapper inside a container as the current user.
# Globals:
#   MAVEN_REPO
#   PROJECT_ROOT
# Arguments:
#   Docker image, extra docker options, "--", then Maven arguments.
#######################################
mvnw_in() {
  local image="$1"
  local -a docker_opts=()
  shift
  while [[ "$1" != '--' ]]; do
    docker_opts+=("$1")
    shift
  done
  shift
  docker run --rm \
    -u "$(id -u):$(id -g)" \
    -e HOME=/tmp/home \
    -e MAVEN_OPTS=-Duser.home=/tmp/home \
    -v "${MAVEN_REPO}:/tmp/home/.m2" \
    -v "${PROJECT_ROOT}:/work" \
    -w /work \
    "${docker_opts[@]}" \
    "${image}" \
    ./mvnw -B "$@"
}

main() {
  local skip_install='false'
  local -a maven_args=()

  while (( $# > 0 )); do
    case "$1" in
      -h | --help)
        usage
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

  # Created up front so Docker does not create it owned by root.
  mkdir -p "${MAVEN_REPO}"

  local -a hide_git=(--mount "type=tmpfs,destination=/work/.git")
  if [[ -f "${PROJECT_ROOT}/.git" ]]; then
    hide_git=(-v /dev/null:/work/.git:ro)
  fi

  if [[ "${skip_install}" == 'false' ]]; then
    stage "[1/3] Building and installing on ${JDK25_IMAGE}"
    mvnw_in "${JDK25_IMAGE}" -- install "${maven_args[@]}"
  fi

  stage "[2/3] Rewriting backport tests with OpenRewrite on ${JDK25_IMAGE}"
  # OpenRewrite skips git-ignored files, and the rewritten sources are
  # git-ignored, so hide the repository from this stage.
  mvnw_in "${JDK25_IMAGE}" "${hide_git[@]}" -- \
    -Pjdk28 -pl jdk28/rewrite-flow -Denforcer.skip \
    clean process-test-sources "${maven_args[@]}"

  stage "[3/3] Verifying against the JDK on ${JDK28_IMAGE}"
  mvnw_in "${JDK28_IMAGE}" -- -Pjdk28 -Drewrite.skip -pl "${JDK28_MODULES}" \
    verify "${maven_args[@]}"

  stage "All JDK 28 flows passed"
}

main "$@"
