# RF Toolkit — project context & handoff

Last updated: 2026-11-05. Read this first when resuming work or planning a new feature.

## 1. Goal

A security/RF research toolkit that runs **entirely on a Galaxy Watch**: Wi-Fi, BLE, NFC, GNSS,
mic and magnetometer only. **No external hardware** (ESP32/nRF/Flipper were explicitly rejected).
Root is **deferred**: the watch can be unlocked, but nothing so far needs it.

Built as a Kotlin Wear OS app. The owner is a Python/C++ developer.

## 2. The device (measured by the Phase 0 probe, not assumed)

| | |
|---|---|
| Model | **SM-R890** = Galaxy Watch4 **Classic**, Bluetooth-only (no LTE / telephony) |
| OS | Wear OS 5 / One UI Watch 6, **API 34**, SoC s5e5515 |
| Bootloader | locked; "OEM unlocking" toggle **enabled** by the owner (`sys.oem_unlock_allowed=1`); Knox intact |
| Wi-Fi | 2.4 **and 5 GHz**; raw beacon IEs available (`ScanResult.getInformationElements`); WPA3/OWE; no RTT/Aware. One full scan ≈ **4.4 s** (incl. passive DFS); apps can't scan a single channel |
| BLE | 2M PHY; **no** coded PHY / extended advertising (31-byte adverts); **HID Device profile available**; offloaded filtering |
| NFC | **Reader mode BLOCKED** (`UnsupportedOperationException`, no `android.hardware.nfc` feature) → **HCE only** (`nfc.hce`, `nfc.hcef`). No observe mode (needs API 35) |
| GNSS | measurements + navigation messages supported; **never tested outdoors yet** (0 sats indoors) |
| Mic | 48 kHz works, including the `UNPROCESSED` source |
| Magnetometer | AK09918C, 100 Hz |
| Battery | 361 mAh; `CURRENT_NOW` is junk (≈ −120) → measure drain via `CHARGE_COUNTER` over time |

Full probe output: run the probe, then `tools/pull.sh probe-latest.json`.

## 3. Status by phase

| Phase | What | Commit | Verified on device |
|---|---|---|---|
| 0 | Capability probe (`modules/probe`) | 7454ba3 | ✅ |
| 1 | City survey: FGS logging Wi-Fi (raw IEs) + BLE (raw adverts) + location + battery → JSONL; presets Low/Balanced/Max | 7454ba3 | ✅ indoors (screen-off collection works). ❌ outdoor GPS walk, ❌ battery drain unplugged |
| 2 | Wi-Fi findings: RSN/WPA/WPS IE parsing, weak security, WPS state, IoT setup-mode SSIDs, default SSIDs, evil-twin detector (TOFU baseline) | 7454ba3 | ✅ scan. ❌ real evil-twin test (switch phone hotspot to open → expect alarm) |
| 3 | BLE trackers: Apple Find My (separated/owner-nearby), Google FMDN, SmartTag, Tile; follow detector (≥3 places ≥150 m ≥15 min); tracker-guard preset | 98ccdab | ✅ found a real separated Apple tracker. ❌ "following" needs an outdoor walk |
| 4 | Fox hunt: smoothed RSSI → haptic rate, trend, 360° body-shadow sweep using compass | 415e2fa | ✅ BLE + Wi-Fi hunt. ❌ sweep (needs physical rotation) |
| 5 | NFC: read-only NFC Forum **Type 4 NDEF tag** via HCE (URL/text), reader log; claims AID via `setPreferredService` (Samsung Health also registers D2760000850101) | 444b22d | ✅ phone read the tag |
| 6 | Network tools: ping, traceroute, DNS vs DoH, TCP port scan (**private IPs only**), subnet sweep, TLS inspector, HTTP security headers | 5b8acf3 | ✅ except sweep/traceroute output |
| 7 | LAN listener: passive mDNS/SSDP/LLMNR inventory, client-isolation verdict | 5b8acf3 | ✅ with `tools/dev/mdns_announce.py` |
| 8 | Threat radar: Wi-Fi channels, jamming monitor (detect only), ultrasonic tone detector (Kotlin FFT), magnetometer | 5b8acf3 | ❌ **not installed yet**: next session |

Unit tests: 129 (`./gradlew testDebugUnitTest`), all pure-Kotlin logic, using fixtures recorded on the watch.

## 4. Next steps

### Phase 8 device test checklist
1. Install the app and open **Threat radar**.
2. **Wi-Fi channels:** bars appear, and the overlap warning makes sense.
3. **Magnetometer:** about 25–65 µT at rest; a fridge magnet or a laptop speaker makes it spike and tick.
4. **Ultrasonic:** idle shows "no tone". Then play a 5 s 18.5 kHz tone from the laptop (owner agreed to try next time) and expect "Tone 18.50 kHz". Generate it with Python and play with `paplay`/`aplay`, at low volume.
5. **Jamming monitor:** reaches "✓ Normal" after 6 windows (~60 s). Never test with a real jammer (illegal); unit tests cover the logic.

### Outstanding field tests
- Outdoor survey walk, unplugged, Balanced preset, 15–20 min. Covers GPS fixes, battery drain (`tools/survey.py summary`), WiGLE export and tracker "following".
- Evil-twin alarm with the phone hotspot switched to open.
- Fox-hunt 360° sweep.

### Small backlog
- Survey on/off **Tile** (planned in Phase 1, not built).
- Privacy option: hash personal SSIDs/BLE names in logs.
- Traceroute/sweep output verification on device.
- C++/NDK port of the FFT (optional learning exercise; NDK is not installed).

## 5. Architecture

```
app/src/main/java/dev/kafli/rftoolkit/
  MainActivity.kt            nav host (SwipeDismissableNavHost), permission launchers, RemoteInput text entry
  core/                      ExportStore (files/export), Haptics (ALARM/NOTICE), Finding/FindingType/Severity
  modules/probe/             CapabilityProbe → probe.json
  modules/survey/            SurveyService (FGS location|connectedDevice), SurveyWriter (JSONL), Records (schema v1),
                             SurveyPreset (LOW/BALANCED/MAX/TRACKER_GUARD), BleFilters, SightingThrottle
  modules/wifi/              Ies (RSN/WPA/WPS parsers), ApProfile, WifiRules, EvilTwinDetector, WifiFindings, WifiScan
  modules/ble/               Adv parser, Trackers classifier, FollowDetector, TrackerWatch, TrackerScan
  modules/hunt/              HuntMath (smoother, proximity, trend, sweep), HuntEngine, TargetScan
  modules/nfc/               Ndef, Type4Tag (APDU state machine), TagStore, TagEmulationService (HCE)
  modules/net/               NetMath, NetTools (+ DnsCompare, SecurityHeaders, TlsReport)
  modules/lan/               Protocols (DnsPacket, Ssdp), LanInventory, LanListener
  modules/radar/             RadarMath (Fft, ToneDetector, ChannelStats, JammingDetector, MagMeter), RadarMonitors
  ui/                        one Compose file per screen
app/src/test/...             JVM tests (real org.json on the test classpath)
tools/pull.sh                pull exports: adb exec-out run-as dev.kafli.rftoolkit cat files/export/<f>
tools/survey.py              summary | wigle | geojson | findings  (stdlib only)
tools/dev/wear_ui.py         drive the watch UI over ADB (texts / tap / scroll)
tools/dev/mdns_announce.py   fake mDNS peer for LAN-listener tests
```

### Conventions (keep them for new features)
- **Pure logic in plain Kotlin** with unit tests, using fixtures recorded on the watch where possible. Android adapters stay thin.
- Each feature has a **process-wide `object` with a `StateFlow`**: `TrackerWatch`, `HuntEngine`, `JamMonitor` and so on.
- **Screen-scoped work** (hunt, LAN, radar, network tools, NFC) starts in `DisposableEffect` and stops on dispose. It sets `keepScreenOn`, so nothing drains in the background. Only `SurveyService` runs as a foreground service.
- **Findings** use `core.Finding(type, id, name, rssi, ms, detail)` and are logged once per session per `key`.
- **Survey JSONL schema v1** (`Records.kt`) has `t` = session | loc | wifi | ble | scan | batt | finding | end. Raw IEs and adverts are stored so old sessions can be re-parsed.
- Network tools call `NetTools.acquireWifi` (`bindProcessToNetwork`), because otherwise Wear routes traffic through the phone over BT.

### Stack
- AGP 8.13.2, Gradle 8.14.3 (wrapper), Kotlin 2.2.21, Java 21 toolchain (target 17).
- Wear Compose Material3 1.5.6, wear-ongoing, wear-input.
- minSdk 30, target/compile 36. Package `dev.kafli.rftoolkit`.

## 6. Dev workflow & pitfalls (learned the hard way)

- **ADB over Wi-Fi only** (the watch has no USB data). Developer options → Wireless debugging → `adb pair IP:PORT CODE` (one line, keep the screen awake), then `adb connect IP:PORT`. The port changes every time Wireless debugging is toggled.
- **Never turn the screen off over ADB.** Wear disables wireless adbd on screen-off; even holding a Wi-Fi network request doesn't help. The owner then has to re-toggle Wireless debugging.
- Keep the watch **on its charger** while developing. "Stay awake" (`stay_on_while_plugged_in=7`) is enabled, so the screen and Wi-Fi stay up.
- **Networks:** eduroam isolates clients, so ADB won't work there. Use the owner's phone hotspot "POCO M6 Pro" (client isolation OFF; IPv6 handed out but not routed).
- **Bad link → `installDebug` hangs.** Ping the watch first (40% loss or ~300 ms means the watch is in power-save or off the charger).
- **Clock:** the watch once showed 2025-03-27. Fixed with `cmd time_detector suggest_network_time --elapsed_realtime <ms> --unix_epoch_time <ms>`. The clock jump triggered permission auto-revoke, so `appops set dev.kafli.rftoolkit AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore` is set. Re-grant with `pm grant` if needed.
- **One-time ADB tweak:** `settings put global wifi_scan_throttle_enabled 0` (already applied).
- **UI automation** (`tools/dev/wear_ui.py`): uiautomator text is localized when it comes from the system (the watch UI language is Ukrainian), but the app's own strings are English.

## 7. Scope rules agreed so far

- **NFC:** the watch emulates **its own** NDEF tag only. No emulation of payment, transit or access cards, and no relaying.
- **Port scanning:** private/local ranges only, enforced in `NetMath.isPrivate`.
- **Jamming:** detection only. Nothing in the app transmits interference.
- **Active features** (port scan, fuzzing, HID, advertising) are for the owner's own devices or authorised targets.
- **Privacy:** the LAN inventory and audio stay in memory. Survey logs contain raw SSIDs/BLE data and stay on the owner's devices.

## 8. Ideas for future features (feasibility from the probe)

- **Bluetooth HID remote:** presentation clicker / media keys. The HID Device profile is available.
- **BLE GATT explorer / beacon emulator:** for the owner's own IoT devices. Legacy advertising only (31 bytes).
- **Tile and complication:** quick survey/guard toggle and a findings count on the watch face.
- **Survey map on the PC:** a folium/Leaflet HTML map from `tools/survey.py geojson`.
- **Root track:** unlocking is now possible. It would add `tcpdump` on the watch's own interfaces, raw-socket scans and iptables. It still gives no monitor mode or NFC reading, and costs a data wipe and Knox.
- **Not possible on this hardware:** Wi-Fi monitor mode or injection, sub-GHz, IR, UWB, cellular (no modem), NFC tag reading.

## 9. Resuming with Claude Code

Open Claude Code in `~/Projects/watch-toolkit`; `CLAUDE.md` points here. Useful first steps:
1. `adb devices`; reconnect if needed (section 6).
2. `./gradlew testDebugUnitTest` to confirm a green baseline.
3. Pick from section 4, or describe a new feature. Check section 2 for whether the hardware allows it.
