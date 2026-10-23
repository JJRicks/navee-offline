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
for name in ChargeScheduleController ChargerDetection Protocol Telemetry ScooterCommands OwnerIdentityParser RideStore RideArchive PhoneRideStore DashboardLayout BatteryCapacityEstimate LearnedRangeEstimate ChargingEstimate OwnerCaptureEngine ScooterSimulation QuickAccessActions DisplayTemperature GaugeScaleInput RangeFollowPolicy RideChartData ChartScale ChargeSessionStore ChargeEstimateHistory ChargeChartData StatsFavouriteOrder AppDataArchive BackupExportCache ConnectionFeedbackState ChargeRecoveryPolicy ConnectionEventLog LockSliderGeometry; do
    sources+=("$APP_DIR/src/com/jjricks/volt/$name.java" "$TEST_DIR/${name}Test.java")
done
sources+=("$APP_DIR/src/com/jjricks/volt/Ut5MaxStockVoltageReference.java" "$TEST_DIR/StockStartupReferenceTest.java" "$TEST_DIR/StockVoltageReferenceTest.java" "$TEST_DIR/StockVoltageReplayTrace.java" "$TEST_DIR/StockVoltageReplayTest.java" "$TEST_DIR/DualChargerEstimateTest.java" "$APP_DIR/src/com/jjricks/volt/Ut5MaxFastChargeReference.java" "$TEST_DIR/Ut5MaxFastChargeReferenceTest.java" "$APP_DIR/src/com/jjricks/volt/ChargeTaperMatcher.java" "$TEST_DIR/StockChargeHoldoutTrace.java" "$TEST_DIR/ChargeTaperMatcherTest.java" "$APP_DIR/src/com/jjricks/volt/OwnerCaptureProcess.java" "$APP_DIR/src/com/jjricks/volt/Ut5MaxStockChargeReference.java" "$APP_DIR/src/com/jjricks/volt/ChargeReferenceGate.java" "$TEST_DIR/CompletedChargeEstimateTest.java" "$TEST_DIR/StockChargeReferenceTest.java" "$TEST_DIR/ChargeReferenceGateTest.java")
sources+=( )
# Compile the actual Android coordinator against test-only binding and preferences APIs.
# These stubs are confined to this test output and never enter the Android build.
for source in "$TEST_DIR"/shizuku-stubs/android/content/*.java \
              "$TEST_DIR"/shizuku-stubs/android/content/pm/*.java \
              "$TEST_DIR"/shizuku-stubs/android/os/*.java \
              "$TEST_DIR"/shizuku-stubs/rikka/shizuku/*.java \
              "$TEST_DIR"/shizuku-stubs/com/jjricks/volt/*.java; do
    sources+=("$source")
done
sources+=("$APP_DIR/src/com/jjricks/volt/ShizukuCaptureClient.java" "$TEST_DIR/ShizukuCaptureClientTest.java")
# Route speed colours (depends on PhoneRideStore and RideStore above).
sources+=("$APP_DIR/src/com/jjricks/volt/RouteSpeedScale.java" "$TEST_DIR/RouteSpeedScaleTest.java")
# Lock slider motion trail.
sources+=("$APP_DIR/src/com/jjricks/volt/LockSliderTrail.java" "$TEST_DIR/LockSliderTrailTest.java")
# Ride auto-end policy and its journal/learned-range effects.
for name in RideAutoStopPolicy; do
    sources+=("$APP_DIR/src/com/jjricks/volt/$name.java" "$TEST_DIR/${name}Test.java")
done
sources+=("$TEST_DIR/RideAutoStopStoreTest.java")
# Unified backup: category registry, archive, plan and transaction (pure Java, temporary files only).
for name in BackupRegistry BackupJson BackupProfiles BackupArchive BackupTransaction; do sources+=("$APP_DIR/src/com/jjricks/volt/$name.java"); done
for name in BackupRegistryTest BackupTestStore BackupArchiveTest BackupPlanTest BackupTransactionTest; do sources+=("$TEST_DIR/$name.java"); done
# Model compatibility suites (separate block to limit merge conflicts with sibling branches).
COMPAT_TESTS=(DeviceDescriptor AdvertisedIdentity StatusFields WireCommands ModelRegistry CapabilityResolver MutationGate ReadPolicy DiscoveryCoordinator CompatibilityReport TestJournal GuidedTest ReportInvite ConnectionReport UpdateCheck DriveModeSurvey NaveeModels IssueReport ModelTiles)
for name in "${COMPAT_TESTS[@]}"; do sources+=("$APP_DIR/src/com/jjricks/volt/$name.java" "$TEST_DIR/${name}Test.java"); done
sources+=("$APP_DIR/src/com/jjricks/volt/FeatureVariant.java" "$TEST_DIR/OemControlsTest.java" "$APP_DIR/src/com/jjricks/volt/OwnerNotes.java")
# Standard-charger pack model (ut5-stock-pack-v6), its generated parity table and charge-age tracking.
for name in Ut5MaxStockPackModel ChargeProgressTracker ChargeMeasureWindow; do sources+=("$APP_DIR/src/com/jjricks/volt/$name.java" "$TEST_DIR/${name}Test.java"); done
sources+=("$TEST_DIR/StockPackParity.java")
javac --release 8 -encoding UTF-8 -d "$TEST_BUILD_DIR/classes" "${sources[@]}"
for name in ChargeScheduleController ChargerDetection Protocol Telemetry ScooterCommands OwnerIdentityParser RideStore RideArchive PhoneRideStore DashboardLayout BatteryCapacityEstimate LearnedRangeEstimate ChargingEstimate OwnerCaptureEngine ScooterSimulation QuickAccessActions DisplayTemperature GaugeScaleInput RangeFollowPolicy RideChartData ChartScale ChargeSessionStore ChargeEstimateHistory ChargeChartData StatsFavouriteOrder AppDataArchive BackupExportCache ConnectionFeedbackState ChargeRecoveryPolicy ConnectionEventLog LockSliderGeometry; do
    java -cp "$TEST_BUILD_DIR/classes" "com.jjricks.volt.${name}Test"
done

java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.Ut5MaxStockPackModelTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ChargeProgressTrackerTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ChargeMeasureWindowTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.CompletedChargeEstimateTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.StockStartupReferenceTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.StockChargeReferenceTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.StockVoltageReplayTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ChargeTaperMatcherTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ChargeReferenceGateTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ShizukuCaptureClientTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.RouteSpeedScaleTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.LockSliderTrailTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.RideAutoStopPolicyTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.RideAutoStopStoreTest

# Unified backup suites.
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.BackupRegistryTest "$APP_DIR/src/com/jjricks/volt"
# These suites run hundreds of synthetic restores; a RAM-backed temporary folder keeps their
# fsyncs fast. AppDataArchiveTest and RideArchiveTest still exercise the normal folder.
# Bash 3.2 treats an empty array as unset under nounset. Keep the command array nonempty.
backup_java=(java)
[[ -d /dev/shm && -w /dev/shm ]] && backup_java+=(-Djava.io.tmpdir=/dev/shm)
for name in BackupArchiveTest BackupPlanTest BackupTransactionTest; do
    (cd "$APP_DIR" && "${backup_java[@]}" -cp "$TEST_BUILD_DIR/classes" "com.jjricks.volt.$name")
done

# From the repo root: ModelRegistryTest checks the bundled model photos.
for name in "${COMPAT_TESTS[@]}" OemControls; do (cd "$APP_DIR" && java -cp "$TEST_BUILD_DIR/classes" "com.jjricks.volt.${name}Test"); done

java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.DualChargerEstimateTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.Ut5MaxFastChargeReferenceTest "$TEST_DIR/fixtures/ut5-max-fast-charge-calibration.csv"

for name in StockVoltageReferenceTest; do java -cp "$TEST_BUILD_DIR/classes" "com.jjricks.volt.$name"; done
