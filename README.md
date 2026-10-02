# HealthBandBPSync

HealthBandBPSync is a reference hardware bridge for reading historical blood-pressure estimates from a J2208-family BLE fitness band and writing those estimates to Android Health Connect.

The Android app is currently presented as **Band BP Sync**. It manually syncs stored `0x56` history records from the band over BLE, keeps the raw bytes and decoded fields in a local Room database, exports diagnostic CSV, and writes valid systolic/diastolic estimates to Health Connect as `BloodPressureRecord` entries.

This is not a medical device, not a cuff replacement, and not medical advice. The values are optical wearable estimates from a consumer band protocol.

## Scope

Working in the current build:

- Scan for and select a compatible J2208-family BLE band.
- Manually sync historical `0x56` HRV/BP records from the band.
- Preserve every raw 15-byte `0x56` record in Room.
- Decode timestamp, HRV, vascular aging, heart rate, stress, systolic BP, and diastolic BP.
- Export local diagnostic data to CSV.
- Write valid BP estimates to Android Health Connect as `BloodPressureRecord`.
- Track Health Connect write status and stable client record IDs per local row.
- Avoid duplicate Health Connect writes using deterministic client record IDs.
- Keep sync manual and close the BLE connection after each sync.

Not implemented by design:

- Background sync / WorkManager.
- Health Connect writes for heart rate, HRV, stress, vascular aging, sleep, or other metrics.
- Live `0x28` measurement handling.
- Reproduction of any vendor app's hourly aggregation or chart display logic.
- Any cloud service.

## Compatibility And Validation

This project targets the J2208-family BLE protocol seen on at least one commercially sold band. It is not affiliated with, endorsed by, or supported by any device vendor.

The current validation status:

- Historical `0x56` records contain a BP-estimate stream with separate systolic and diastolic fields.
- Decoded systolic/diastolic values match the apparent blood-pressure history behavior of the companion app used during testing.
- Health Connect writes show the expected timestamps and values.
- Repeated sync/write cycles are designed to be idempotent.

Further validation is still useful:

- Compare band estimates against cuff readings taken near the same timestamps.
- Inspect Health Connect / Google export data after several days of manual syncing.
- Continue checking for duplicate records after repeated writes.

## Protocol Summary

The implemented protocol details live in:

`android/HumeBridge/app/src/main/java/dev/erban/humebridge/protocol/J2208Protocol.kt`

The Android project path and Kotlin package still contain the earlier internal codename. Renaming those would require an app/package migration and is intentionally deferred to avoid breaking installed test builds or local Health Connect dedupe state.

BLE UUIDs:

- Service: `0000fff0-0000-1000-8000-00805f9b34fb`
- Write characteristic: `0000fff6-0000-1000-8000-00805f9b34fb`
- Notify characteristic: `0000fff7-0000-1000-8000-00805f9b34fb`

Historical HRV/BP command:

- Opcode: `0x56`
- Start mode: `0`
- Continue mode: `2`
- Terminator pair: `56 ff`
- Record stride: 15 bytes

Decoded `0x56` record layout:

| Offset | Field | Decode |
| --- | --- | --- |
| 0 | Opcode | `0x56` |
| 1-2 | Sequence / unknown | preserved in raw record |
| 3 | Year | BCD, interpreted as `2000 + yy` |
| 4 | Month | BCD |
| 5 | Day | BCD |
| 6 | Hour | BCD |
| 7 | Minute | BCD |
| 8 | Second | BCD |
| 9 | HRV | unsigned byte |
| 10 | Vascular aging | unsigned byte |
| 11 | Heart rate | unsigned byte |
| 12 | Stress | unsigned byte |
| 13 | Systolic BP estimate | unsigned byte, mmHg |
| 14 | Diastolic BP estimate | unsigned byte, mmHg |

BP validity is currently:

```text
bpSystolic > 0 && bpDiastolic > 0
```

Heart rate is not required for BP validity.

## Health Connect Behavior

Valid BP rows are written as `BloodPressureRecord` values:

- Systolic and diastolic are written directly in mmHg.
- No averaging, smoothing, aggregation, calibration, or transformation is applied.
- The decoded device timestamp is preserved.
- The local zone offset at decode time is preserved.
- Body position and measurement location are set to Health Connect's unknown/default values.
- Metadata marks the device as a J2208-compatible fitness band.

Each row gets a stable Health Connect client record ID. The prefix currently remains the original project prefix for compatibility with records already written during validation:

```text
hume-j2208-bp:{bandAddress}:{deviceTimeLocal}:{rawSha256}
```

Do not change that prefix without also planning a migration, or previously written records may be inserted again under new client IDs.

## Privacy And Repository Hygiene

This project can create files containing personal health data. Do not commit:

- CSV exports.
- SQLite databases.
- Captures and notes.
- APKs pulled from vendor apps.
- Decompiled vendor output.
- Health Connect exports.
- Local Android Studio files such as `local.properties`.

The `.gitignore` is set up for these categories, but if a sensitive or generated file was committed before being ignored, remove it from the Git index with:

```powershell
git rm --cached path/to/file
```

## Android App

Project path:

```text
android/HumeBridge
```

Visible app name:

```text
Band BP Sync
```

Package:

```text
dev.erban.humebridge
```

Minimum Android version:

```text
minSdk 26
```

The app requires:

- Bluetooth LE.
- Bluetooth scan/connect permissions.
- Health Connect blood-pressure read/write permissions for write verification.

## Build

From the Android project directory:

```powershell
cd android\HumeBridge
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Debug APK output:

```text
android/HumeBridge/app/build/outputs/apk/debug/app-debug.apk
```

## Manual Use Flow

1. Install the debug APK on an Android phone with Health Connect available.
2. Open Band BP Sync.
3. Grant BLE permissions.
4. Scan for the band.
5. Select the compatible J2208-family device.
6. Tap `Sync 0x56` to pull stored records.
7. Export CSV if you want to inspect the raw/decoded local data.
8. Grant Health Connect BP permission.
9. Tap `Write BP to Health Connect`, or use `Sync Band + Write BP` for the normal combined manual flow.
10. Confirm the app shows eligible BP rows written with zero pending/failed eligible rows.

Because the band appears to retain only a limited recent history window, sync at least daily during validation.

## CSV Export

CSV export includes:

- `device_time_local`
- `instant_utc`
- `hrv`
- `vascular_aging`
- `heart_rate`
- `stress`
- `bp_systolic`
- `bp_diastolic`
- `raw_hex`
- `raw_sha256`
- `first_fetched_at_epoch_ms`
- `bp_provenance`
- `health_connect_status`
- `health_connect_client_record_id`
- `health_connect_written_at_epoch_ms`
- `health_connect_last_error`

Raw records are preserved even when BP is invalid (`0/0`). Invalid BP records are not written to Health Connect.

## Development Notes

Useful files:

- Protocol parser: `android/HumeBridge/app/src/main/java/dev/erban/humebridge/protocol/J2208Protocol.kt`
- BLE sync: `android/HumeBridge/app/src/main/java/dev/erban/humebridge/ble/J2208GattClient.kt`
- Room entities/DAOs: `android/HumeBridge/app/src/main/java/dev/erban/humebridge/data/`
- Health Connect bridge: `android/HumeBridge/app/src/main/java/dev/erban/humebridge/health/HealthConnectBpBridge.kt`
- UI/view model: `android/HumeBridge/app/src/main/java/dev/erban/humebridge/MainActivity.kt` and `MainViewModel.kt`
- Protocol tests: `android/HumeBridge/app/src/test/java/dev/erban/humebridge/protocol/J2208ProtocolTest.kt`

When changing protocol decoding, keep raw bytes and hashes intact so earlier interpretations can be revisited.
