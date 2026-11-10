#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -x "$HOME/.jdks/jbr-21.0.11/bin/java" ]]; then
  export JAVA_HOME="$HOME/.jdks/jbr-21.0.11"
fi
if [[ -z "${GRADLE_USER_HOME:-}" && -d "$PWD/.gradle-home" ]]; then
  export GRADLE_USER_HOME="$PWD/.gradle-home"
fi
exec ./gradlew "$@"
