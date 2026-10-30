#!/usr/bin/env bash
# Builds the signed release APK locally and names it like a GitHub release asset, with its .sha256.
# Needs ~/.tvcouchbrowser/keystore.properties. Usage: tools/release-local.sh
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
./gradlew --no-daemon -q assembleRelease
NAME="tvcouchbrowser-${VERSION}-universal.apk"
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/$NAME"
(cd dist && sha256sum "$NAME" > "$NAME.sha256")
ls -l dist/"$NAME"; cat dist/"$NAME.sha256"
