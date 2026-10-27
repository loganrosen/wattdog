# Wattdog

Wattdog finds out where your Android battery went. It reads the same `dumpsys batterystats` and `dumpsys power` output you'd pull over adb, parses it on the device, and flags the usual culprits: stuck audio wakelocks, wakeup-alarm storms, cellular modem standby, poor deep doze, and expensive always-on display. Root isn't required.

Think of it as a modern BetterBatteryStats for Android 14+.

## What it shows

- **Today**: a plain-language verdict on where the battery went, split into screen time and screen-off drain, and whether the idle rate is light, a bit high, or heavy. Those bands are rough guides (under 1% an hour light, 2% or more heavy, with extra room for always-on display time), since nobody publishes a healthy figure. A rate only just past the heavy line is called "on the high side" rather than heavy. When idle drain is high it names the biggest parts. It recommends a fix only when one app or setting clearly stands out and there's something to change. Otherwise it says there's no clear culprit and suggests a quiet overnight check. It then lists the apps that used the most. **Technical details** at the bottom has the component breakdown, Doze, AOD, wake sources, and the full anomaly list. Problems still active right now (from `dumpsys power`) get an "active now" badge.
- **Guided tests**: when a fix opens a system setting, tap **I changed it: track the result** after changing it. Wattdog only judges quiet stretches: six hours or more unplugged with the screen off, rarely woken, on one network, like a night on the nightstand. It compares each one since the change with at least three similar ones from the two weeks before (same network and always-on display setting) and says whether drain was lower, higher, or within the earlier range. Otherwise it shows the drain and says it can't compare. It never claims the change caused a difference, since signal and background activity move drain too.
- **Trends**: your recent range, learned from your own use. After five stretches of a couple of hours with the screen off, over at least three days, Today also says whether idle drain is higher than usual. History never changes the verdict itself: a phone that always drains fast would otherwise look fine. Below that are a by-day summary and a timeline from `dumpsys batterystats --history` with the battery level, charging, and lanes for screen, AOD, awake, wakelocks, Doze, cellular and Wi-Fi radio, signal, GPS, BLE scans, and media. Drag across it to see what was running at any moment.
- **Apps**: per-UID power estimates with foreground/background split, jobs, wakeup alarms, wakelocks, and network use. UIDs are resolved to app names, including shared and system UIDs.
- **Diagnostics**: app partial wakelocks (filterable by audio/jobs/sync/alarms/location, held locks marked, stuck timers flagged as phantom), kernel wakelocks, and wakeup reasons, plus radios: cellular drain split into app traffic vs standby, cost per MB, signal-strength and network-type breakdowns, which apps keep the modem awake, and Wi-Fi, Bluetooth scanning, and GPS.
- **Time windows**: the title menu switches between since unplugged, recent windows (last hour, last 3 or 6 hours, and overnight in the morning) built from automatic snapshots, and your own marks. Use **Mark now…** before a test, like switching to LTE, then pick the mark later to see only what happened since. Marks from before a charge are greyed out because the stats were reset.
- **Background checks** (off by default, in Settings): every hour on battery Wattdog saves an automatic snapshot (kept for two days, and dropped once the stats reset), diffs the stats against one from about three hours earlier, and notifies about new or worsening findings (warnings and above) from that window, with buttons for the relevant screen or system setting. Each check also adds to the usage log behind your recent range, so it fills in without opening the app. The same finding won't notify again for 12 hours unless it gets worse. If Shizuku stops (for example after a reboot) and checks keep failing, you get one quiet notice.

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

Dumps never leave the device. Snapshots are stored gzipped in app-private storage and are excluded from backup. The usage log behind the baseline holds only drain totals and is kept for 90 days. They contain package names, account-related wakelock tags, and device identifiers, so be careful about sharing them.

## Project layout

```
core/batterystats/   Pure Kotlin/JVM: models, parsers, metrics, anomaly rules, snapshot deltas. Unit-tested.
app/                 Android app
  privileged/        Shizuku status + user service (AIDL), direct DUMP-permission path, dumpsys allowlist
  data/              Snapshot store, UID → app resolver, repository
  alerts/            Background checks: WorkManager job, automatic snapshots, notifications
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
