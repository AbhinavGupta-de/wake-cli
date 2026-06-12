#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /usr/libexec/java_home ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21+ 2>/dev/null)"
  else
    echo "JAVA_HOME is required" >&2
    exit 1
  fi
fi
export JAVA_HOME

MVN="${MVN:-/opt/homebrew/bin/mvn}"
if [[ ! -x "$MVN" ]]; then
  MVN="$(command -v mvn)"
fi

"$MVN" -q -DskipTests package
"$JAVA_HOME/bin/native-image" --no-fallback -O2 -jar target/wake.jar target/wake
