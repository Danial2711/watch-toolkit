# RF Toolkit for Wear OS

A security and RF research toolkit that runs **entirely on a smartwatch**: a Galaxy Watch4 Classic
(SM-R890, Wear OS 5 / API 34). It uses only the watch's own radios and sensors (Wi-Fi, BLE, NFC, GNSS,
microphone, magnetometer). No external hardware and no root.

Written in Kotlin with Jetpack Compose for Wear OS. All detection logic is plain Kotlin covered by
**129 JVM unit tests**, many of them using fixtures recorded on the real watch.

## Features

| # | Module | What it does |
|---|---|---|
| 0 | **Capability probe** | Measures what the hardware actually allows (Wi-Fi bands and IEs, BLE PHYs, NFC modes, mic rates, sensors) and exports `probe.json` |
| 1 | **City survey** | Foreground service logging Wi-Fi (raw beacon IEs), BLE (raw adverts), location and battery to JSONL, with Low/Balanced/Max presets |
| 2 | **Wi-Fi findings** | RSN/WPA/WPS IE parsing: weak or open security, WPS state, IoT setup-mode and default SSIDs, plus an **evil-twin detector** (trust-on-first-use baseline catching security downgrades and unknown BSSIDs) |
| 3 | **BLE tracker detection** | Classifies Apple Find My (separated vs. owner nearby), Google FMDN, Samsung SmartTag and Tile adverts. A follow detector alerts when a tracker is seen at ≥3 places ≥150 m apart over ≥15 min |
| 4 | **Fox hunt** | Smoothed RSSI → haptic feedback rate and trend, plus a 360° body-shadow sweep using the compass to estimate direction |
| 5 | **NFC tag** | The watch acts as a read-only NFC Forum Type 4 NDEF tag (URL/text) via host card emulation, with a reader log |
| 6 | **Network tools** | Ping, traceroute, DNS vs. DoH comparison, TCP port scan (**private ranges only**), subnet sweep, TLS inspector, HTTP security headers |
| 7 | **LAN listener** | Passive mDNS/SSDP/LLMNR inventory and a client-isolation verdict |
| 8 | **Threat radar** | Wi-Fi channel congestion, jamming monitor (**detect only**), ultrasonic beacon detector (17.5–22 kHz, Kotlin FFT), magnetometer |

Status per phase, including what has and hasn't been verified on the device, is in
[docs/CONTEXT.md](docs/CONTEXT.md#3-status-by-phase).

## Scope and ethics

- **NFC:** emulates only its own NDEF tag. No payment, transit or access-card emulation, no relaying.
- **Port scanning:** restricted to private/local address ranges (enforced in `NetMath.isPrivate`).
- **Jamming:** detection only. Nothing in the app transmits interference.
- **Privacy:** LAN inventory and audio stay in memory. Survey logs stay on the owner's devices.

## Build and run

Requirements: JDK 21, Android SDK (compileSdk 36), a Wear OS watch with wireless debugging.

```bash
./gradlew testDebugUnitTest      # JVM unit tests
adb pair <ip>:<port> <code>      # Developer options → Wireless debugging
adb connect <ip>:<port>
./gradlew installDebug
```

Exports land in the app's `files/export` directory:

```bash
tools/pull.sh survey-XXXX.jsonl                  # adb exec-out run-as … cat
tools/survey.py summary tools/out/survey-XXXX.jsonl
tools/survey.py wigle   tools/out/survey-XXXX.jsonl -o wigle.csv
tools/survey.py geojson tools/out/survey-XXXX.jsonl -o survey.geojson
```

## Layout

```
app/src/main/java/dev/kafli/rftoolkit/
  core/              export store, haptics, findings model
  modules/probe|survey|wifi|ble|hunt|nfc|net|lan|radar
  ui/                one Compose file per screen
app/src/test/        JVM unit tests
tools/               export pulling, survey analysis (Python, stdlib only), dev helpers
docs/CONTEXT.md      device capabilities, architecture, conventions, pitfalls, roadmap
```

## Built with Claude Code

This project was developed with [Claude Code](https://claude.com/claude-code). `CLAUDE.md` and
`docs/CONTEXT.md` are the handoff documents that let each session pick up where the last one stopped.
They also record the hardware probe results and the dev-workflow pitfalls found along the way.

## License

[Apache-2.0](LICENSE)
