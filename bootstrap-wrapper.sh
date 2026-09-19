#!/usr/bin/env bash
# Fetch gradle-wrapper.jar when it is missing from a fresh clone.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
DEST="$ROOT/gradle/wrapper/gradle-wrapper.jar"
URL="https://github.com/gradle/gradle/raw/v8.7.0/gradle/wrapper/gradle-wrapper.jar"

if [[ -f "$DEST" ]]; then
	echo "gradle-wrapper.jar already present"
	exit 0
fi

mkdir -p "$(dirname "$DEST")"
curl -fsSL -o "$DEST" "$URL"
chmod 644 "$DEST"
echo "Downloaded $DEST"
