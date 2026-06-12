#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

OS="$(uname -s)"
case "$OS" in
  Darwin)
    if [[ -z "${JAVA_HOME:-}" ]]; then
      if [[ -x /usr/libexec/java_home ]]; then
        JAVA_HOME="$(/usr/libexec/java_home -v 21+ 2>/dev/null || true)"
      fi
      if [[ -z "${JAVA_HOME:-}" ]]; then
        echo "JAVA_HOME is required; set it to a GraalVM JDK 21+ home" >&2
        exit 1
      fi
    fi
    ;;
  Linux)
    if [[ -z "${JAVA_HOME:-}" ]]; then
      echo "JAVA_HOME is required on Linux; set it to a GraalVM JDK 21+ home" >&2
      exit 1
    fi
    ;;
  *)
    echo "unsupported build platform: $OS" >&2
    exit 1
    ;;
esac
export JAVA_HOME

resolve_mvn() {
  if [[ -n "${MVN:-}" ]]; then
    if [[ -x "$MVN" ]]; then
      printf '%s\n' "$MVN"
      return
    fi
    if command -v "$MVN" >/dev/null 2>&1; then
      command -v "$MVN"
      return
    fi
    echo "MVN points to a non-executable command: $MVN" >&2
    exit 1
  fi

  if [[ "$OS" == "Darwin" && -x /opt/homebrew/bin/mvn ]]; then
    printf '%s\n' /opt/homebrew/bin/mvn
    return
  fi

  if command -v mvn >/dev/null 2>&1; then
    command -v mvn
    return
  fi

  echo "mvn is required on PATH, or set MVN to a Maven executable" >&2
  exit 1
}

MVN="$(resolve_mvn)"
if [[ ! -x "$JAVA_HOME/bin/native-image" ]]; then
  echo "native-image not found at $JAVA_HOME/bin/native-image; use a GraalVM JDK" >&2
  exit 1
fi

"$MVN" -q -DskipTests package
"$JAVA_HOME/bin/native-image" --no-fallback -O2 -jar target/wake.jar target/wake
