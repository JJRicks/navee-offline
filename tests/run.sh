#!/usr/bin/env bash
# Pure Java tests: no Android SDK, phone, internet connection, or signing key.
set -euo pipefail
umask 077
TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$TEST_DIR/../build.sh"
navee_find_java
TEST_BUILD_DIR="$NAVEE_BUILD_DIR/core-tests"
rm -rf -- "$TEST_BUILD_DIR/classes"
mkdir -p "$TEST_BUILD_DIR/classes"
sources=()
for name in Protocol Telemetry ScooterCommands OwnerIdentityParser RideStore PhoneRideStore DashboardLayout BatteryCapacityEstimate ChargingEstimate OwnerCaptureEngine ScooterSimulation QuickAccessActions DisplayTemperature GaugeScaleInput; do
    sources+=("$APP_DIR/src/local/navee/offlinelock/$name.java" "$TEST_DIR/${name}Test.java")
done
sources+=("$APP_DIR/src/local/navee/offlinelock/OwnerCaptureProcess.java")
# Compile the actual Android coordinator against test-only binding and preferences APIs.
# These stubs are confined to this test output and never enter the Android build.
for source in "$TEST_DIR"/shizuku-stubs/android/content/*.java \
              "$TEST_DIR"/shizuku-stubs/android/content/pm/*.java \
              "$TEST_DIR"/shizuku-stubs/android/os/*.java \
              "$TEST_DIR"/shizuku-stubs/rikka/shizuku/*.java \
              "$TEST_DIR"/shizuku-stubs/local/navee/offlinelock/*.java; do
    sources+=("$source")
done
sources+=("$APP_DIR/src/local/navee/offlinelock/ShizukuCaptureClient.java" "$TEST_DIR/ShizukuCaptureClientTest.java")
javac --release 8 -encoding UTF-8 -d "$TEST_BUILD_DIR/classes" "${sources[@]}"
for name in Protocol Telemetry ScooterCommands OwnerIdentityParser RideStore PhoneRideStore DashboardLayout BatteryCapacityEstimate ChargingEstimate OwnerCaptureEngine ScooterSimulation QuickAccessActions DisplayTemperature GaugeScaleInput; do
    java -cp "$TEST_BUILD_DIR/classes" "local.navee.offlinelock.${name}Test"
done

java -cp "$TEST_BUILD_DIR/classes" local.navee.offlinelock.ShizukuCaptureClientTest
