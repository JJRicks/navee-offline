#!/usr/bin/env bash
# Build the separate native validation runner with the same SDK and signing key.
set -euo pipefail
umask 077
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../build.sh"
navee_find_sdk
navee_find_java
APP_JAR="$BUILD_DIR/compiled.jar"
[[ -f "$APP_JAR" ]] || { echo "Build the main app first: bash build.sh" >&2; exit 1; }
navee_load_signing_identity false
TEST_BUILD_DIR="$NAVEE_BUILD_DIR/parked-validation"
rm -rf -- "$TEST_BUILD_DIR/classes" "$TEST_BUILD_DIR/dex"
mkdir -p "$TEST_BUILD_DIR/classes" "$TEST_BUILD_DIR/dex"
"$BUILD_TOOLS/aapt" package -f -M "$SCRIPT_DIR/AndroidManifest.xml" \
    -I "$ANDROID_JAR" -F "$TEST_BUILD_DIR/resources.apk"
javac --release 8 -encoding UTF-8 \
    -cp "$ANDROID_JAR:$BUILD_TOOLS/core-lambda-stubs.jar:$APP_JAR" \
    -d "$TEST_BUILD_DIR/classes" \
    "$SCRIPT_DIR/ParkedValidation.java" "$SCRIPT_DIR/LaunchValidation.java" \
    "$SCRIPT_DIR/PhoneRideValidation.java" "$SCRIPT_DIR/UiOrganizationValidation.java" \
    "$SCRIPT_DIR/DashboardValidation.java" "$SCRIPT_DIR/HistoryValidation.java" \
    "$SCRIPT_DIR/SearchFocusValidation.java" "$SCRIPT_DIR/WindowTouchTrace.java" \
    "$SCRIPT_DIR/MapTilesValidation.java" "$SCRIPT_DIR/DashboardSlotsValidation.java" \
    "$SCRIPT_DIR/ResponsiveDashboardValidation.java" "$SCRIPT_DIR/DeveloperSettingsValidation.java" \
    "$SCRIPT_DIR/BatteryWidgetValidation.java"
jar cf "$TEST_BUILD_DIR/runner.jar" -C "$TEST_BUILD_DIR/classes" .
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --classpath "$APP_JAR" --min-api 26 \
    --output "$TEST_BUILD_DIR/dex" "$TEST_BUILD_DIR/runner.jar"
cp "$TEST_BUILD_DIR/resources.apk" "$TEST_BUILD_DIR/unsigned.apk"
zip -q -j "$TEST_BUILD_DIR/unsigned.apk" "$TEST_BUILD_DIR"/dex/classes*.dex
"$BUILD_TOOLS/zipalign" -P 16 -f 4 "$TEST_BUILD_DIR/unsigned.apk" "$TEST_BUILD_DIR/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$SIGNING_KEYSTORE" \
    --ks-key-alias "$SIGNING_ALIAS" --ks-pass env:NAVEE_SIGNING_PASSWORD \
    --key-pass env:NAVEE_SIGNING_PASSWORD \
    --out "$TEST_BUILD_DIR/parked-validation.apk" "$TEST_BUILD_DIR/aligned.apk"
"$BUILD_TOOLS/apksigner" verify "$TEST_BUILD_DIR/parked-validation.apk"
"$BUILD_TOOLS/zipalign" -c -P 16 4 "$TEST_BUILD_DIR/parked-validation.apk"
echo "Built and verified separate validation APK: $TEST_BUILD_DIR/parked-validation.apk"
