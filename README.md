# Wattdog

Wattdog finds out where your Android battery went. It reads the same `dumpsys batterystats` and `dumpsys power` output you'd pull over adb, parses it on the device, and flags the usual culprits: stuck audio wakelocks, wakeup-alarm storms, cellular modem standby, poor deep doze, and expensive always-on display. Root isn't required.

Think of it as a modern BetterBatteryStats for Android 14+.

## What it shows

- **Dashboard**: discharge since unplugged (or since a snapshot), screen-on vs screen-off mAh/h, how long the CPU stayed awake with the screen off, top apps and power components, Doze and AOD, plus an anomalies card. Problems still active right now (from `dumpsys power`) get an "active now" badge.
- **History**: a timeline from `dumpsys batterystats --history` with the battery level, charging, and lanes for screen, AOD, awake, wakelocks, Doze, cellular and Wi-Fi radio, signal, GPS, BLE scans, and media. Drag across it to see what was running at any moment. Below that: drain rates and the foreground apps, wakelocks, and jobs from the history for the same window.
- **Wakelocks**: app partial wakelocks (filterable by audio/jobs/sync/alarms/location, held locks marked), kernel wakelocks, and wakeup reasons.
- **Radios**: cellular drain split into app traffic vs standby, cost per MB, signal-strength and network-type breakdowns, which apps keep the modem awake, plus Wi-Fi, Bluetooth scanning, and GPS.
- **Apps**: per-UID power estimates with foreground/background split, jobs, wakeup alarms, wakelocks, and network use. UIDs are resolved to app names, including shared and system UIDs.
- **Snapshots**: tap + to save a baseline (say, before bed), then pick it from the title menu to see only what happened since.
- **Background checks** (off by default, in Settings): every few hours Wattdog diffs the stats against its previous check and notifies about new or worsening findings (warnings and above) from that window, with buttons for the relevant screen or system setting. The same finding won't notify again for 12 hours unless it gets worse. If Shizuku stops (for example after a reboot) and checks keep failing, you get one quiet notice.

## Access

`dumpsys batterystats` needs shell-level permissions. Wattdog supports two ways to get them:

1. **Shizuku** (recommended). Install [Shizuku](https://shizuku.rikka.app/), start it with wireless debugging, and allow Wattdog when asked. Wattdog runs `dumpsys` inside a small Shizuku user service, so no computer is needed after setup.
2. **One-time adb grant**. From a computer:

   ```sh
   adb shell pm grant io.github.loganrosen.wattdog android.permission.DUMP
   adb shell pm grant io.github.loganrosen.wattdog android.permission.PACKAGE_USAGE_STATS
   ```

   These grants survive reboots but not a reinstall. Batterystats checks both permissions.

Battery stats only accumulate while on battery. If you charge to full and then unplug, Android resets them, and snapshots taken before the reset can no longer be used as baselines.

## Privacy

Dumps never leave the device. Snapshots are stored gzipped in app-private storage and are excluded from backup. They contain package names, account-related wakelock tags, and device identifiers, so be careful about sharing them.

## Project layout

```
core/batterystats/   Pure Kotlin/JVM: models, parsers, metrics, anomaly rules, snapshot deltas. Unit-tested.
app/                 Android app
  privileged/        Shizuku status + user service (AIDL), direct DUMP-permission path, dumpsys allowlist
  data/              Snapshot store, UID → app resolver, repository
  alerts/            Background checks: WorkManager job, window checkpoint, notifications
  ui/                Compose Material 3 screens
```

The parser and analysis live in a plain JVM module so they can be tested against real dumps without an emulator. Dependency wiring is manual (`AppContainer`).

## Building

Requires JDK 17+ and the Android SDK (compileSdk 37).

```sh
./gradlew :core:batterystats:test      # parser and analysis tests
./gradlew :app:assembleDebug
./gradlew spotlessApply                # ktlint formatting, including Compose rules
./gradlew :app:lintDebug
```

CI runs all of these, plus a release (R8) build, on every push to `main` and on pull requests.

Most fixtures under `core/batterystats/src/test/resources` are synthetic. The ones in `real/` are captures from a Pixel on Android 17 and an API 34 emulator, run through `scripts/anonymize_dump.py`. It swaps third-party package names for stable placeholders and strips emails, MAC and IP addresses, and long digit runs, keeping the numbers intact. Run any new real dump through it and review the result before committing; raw `*.dumpsys.txt` and `dumps/` are gitignored.

```sh
adb shell cmd package list packages -U --user 0 > packages.txt
adb shell dumpsys batterystats --charged > raw.txt
scripts/anonymize_dump.py --packages packages.txt < raw.txt | gzip > charged.txt.gz
```

## License

Wattdog is licensed under the GNU General Public License v3.0 or later. See [LICENSE](LICENSE). You can fork and redistribute it, including on app stores, as long as you publish your source under the same license.
