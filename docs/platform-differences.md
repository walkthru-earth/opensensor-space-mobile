# Android and iOS differences that stay

**Scope:** every user-visible feature that intentionally differs between the two apps after the
parity work, and why. Everything not listed here is shared Kotlin and behaves the same.
**Code:** the linked Android files and `ios/App/`. **Related:**
[platform-parity-plan.md](platform-parity-plan.md),
[parity-audit/F-feature-inventory.md](parity-audit/F-feature-inventory.md) (full feature table),
[parity-decisions.md](parity-decisions.md).

## Android only

| Feature | Android | Why iOS does not have it |
| --- | --- | --- |
| Sync when the phone joins home Wi-Fi (F70) | `data/background/SyncTriggers.kt`, `WifiArrivalReceiver` | iOS gives a suspended app no public wake on network change. `NEHotspotHelper` is a restricted entitlement |
| Re-arm triggers after reboot or update (F71) | `data/background/BootReceiver.kt` | `BGTaskScheduler` requests and Core Bluetooth state restoration already persist across launches |
| Wi-Fi lock during a LAN transfer (F72) | `data/lan/LanWifiLock.kt` | No iOS API and no need, the radio stays up while the app runs |
| Companion Device Manager exemptions (F73) | `data/background/CompanionPresence.kt` | No iOS equivalent. iOS uses a pending Core Bluetooth connect instead (F54) |
| Archive offered read-only through a FileProvider (F81) | `res/xml/file_paths.xml` | iOS exports through Share and Save only. Exposing the archive in the Files app would let users rename or delete it |

## Same feature, different OS mechanism

| Feature | Android | iOS |
| --- | --- | --- |
| Turn Bluetooth on (F23) | System enable dialog | Apps cannot turn Bluetooth on. The system power alert and shared copy explain it |
| Open Bluetooth settings after a bond fails (F24) | Bluetooth settings intent | No public Bluetooth settings URL. Shared repair copy plus the app settings link |
| Local network permission (F76) | Runtime permission on API 37 and later | No status API. A `PolicyDenied` browse result counts as denied |
| Wake near the logger (F54, F69) | Companion Device Manager presence and an offloaded advert scan | A pending `CBCentralManager.connect` with state restoration. Advert flag changes are not reliable on iOS because duplicate adverts are coalesced |
| Periodic Auto-sync (F68) | WorkManager every 15 min, battery not low | `BGProcessingTaskRequest`. The system picks the time |
| Sync in background with progress (F48, F66) | Foreground service with a progress notification | `BGContinuedProcessingTask` on iOS 26 and later. Before that, a short background lease and a "keep the app open" hint |
| Notifications permission (F77) | Asked from the first Sync or the Auto-sync switch | Asked from the Auto-sync switch only |
| LAN token at rest (F80) | Encrypted with an Android Keystore key | Keychain, `AfterFirstUnlockThisDeviceOnly` |
| Backup (F79) | Settings to cloud, archive device-to-device only | Settings backed up, archive excluded from backup |
| Sticker barcode scan (F52) | Google Code Scanner, no camera permission | VisionKit `DataScannerViewController`, camera asked on the Scan tap |
