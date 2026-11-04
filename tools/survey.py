#!/usr/bin/env python3
"""Analyze RF Toolkit survey logs (JSONL schema v1, see app/.../survey/Records.kt).

Usage:
  tools/survey.py summary  tools/out/survey-XXXX.jsonl
  tools/survey.py wigle    tools/out/survey-XXXX.jsonl  [-o out.csv]   # WiGLE CSV 1.4 (rows with location only)
  tools/survey.py geojson  tools/out/survey-XXXX.jsonl  [-o out.geojson]
  tools/survey.py findings tools/out/survey-XXXX.jsonl              # Wi-Fi + BLE tracker findings ("finding" rows)

Standard library only.
"""
import argparse
import collections
import datetime as dt
import json
import sys


def load(path):
    rows, bad = [], 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                bad += 1  # last line may be cut if the app was killed mid-write
    if bad:
        print(f"warning: skipped {bad} malformed line(s)", file=sys.stderr)
    return rows


def ts(ms):
    return dt.datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M:%S")


def wigle_auth(caps):
    # WiGLE expects Android-style capability strings, e.g. "[WPA2-PSK-CCMP][ESS]".
    return caps or "[ESS]"


def cmd_summary(rows, _args):
    head = next((r for r in rows if r["t"] == "session"), {})
    end = next((r for r in rows if r["t"] == "end"), None)
    counts = collections.Counter(r["t"] for r in rows)
    print(f"session {head.get('id')}  preset={head.get('preset')}  device={head.get('device', {}).get('model')}")
    times = [r["ms"] for r in rows if "ms" in r]
    if times:
        print(f"span    {ts(min(times))} → {ts(max(times))}  ({(max(times) - min(times)) / 60000:.1f} min)")
    print(f"records {dict(counts)}  clean-stop={'yes' if end else 'NO'}")

    wifi = [r for r in rows if r["t"] == "wifi"]
    aps = {}
    for r in wifi:
        a = aps.setdefault(r["bssid"], {"ssid": r["ssid"], "caps": r["caps"], "freq": r["freq"], "ch": r["ch"], "best": -999, "n": 0})
        if r["rssi"] not in (None, 0):  # 0 = driver quirk in pre-v1 logs
            a["best"] = max(a["best"], r["rssi"])
        a["n"] += 1
    bands = collections.Counter("5" if a["freq"] > 4000 else "2.4" for a in aps.values())
    sec = collections.Counter()
    for a in aps.values():
        c = a["caps"]
        if "WEP" in c:
            sec["WEP"] += 1
        elif "SAE" in c:
            sec["WPA3"] += 1
        elif "EAP" in c:
            sec["Enterprise"] += 1
        elif "WPA2" in c or "RSN" in c:
            sec["WPA2"] += 1
        elif "WPA" in c:
            sec["WPA1"] += 1
        elif "OWE" in c:
            sec["OWE"] += 1
        else:
            sec["OPEN"] += 1
    print(f"wifi    {len(aps)} unique APs  bands={dict(bands)}  security={dict(sec)}")

    ble = [r for r in rows if r["t"] == "ble"]
    addrs = collections.Counter(r["addr"] for r in ble)
    print(f"ble     {len(addrs)} unique addresses, {len(ble)} logged adverts")

    locs = [r for r in rows if r["t"] == "loc"]
    if locs:
        acc = sorted(r["acc"] for r in locs)
        print(f"loc     {len(locs)} fixes, median accuracy {acc[len(acc) // 2]:.0f} m, providers={dict(collections.Counter(r['prov'] for r in locs))}")
    else:
        print("loc     no fixes (indoors / location off?)")
    with_loc = sum(1 for r in wifi + ble if r.get("loc"))
    print(f"        {with_loc}/{len(wifi) + len(ble)} sightings carry a location")

    batt = [r for r in rows if r["t"] == "batt"]
    if len(batt) >= 2:
        hours = (batt[-1]["ms"] - batt[0]["ms"]) / 3.6e6
        dpct = batt[0]["pct"] - batt[-1]["pct"]
        dchg = (batt[0]["chg"] - batt[-1]["chg"]) / 1000
        if dpct < 0:
            print(f"battery charging during session ({batt[0]['pct']}% → {batt[-1]['pct']}%) — drain not measurable")
        elif hours > 0:
            print(f"battery {batt[0]['pct']}% → {batt[-1]['pct']}%  ≈ {dpct / hours:.1f} %/h, {dchg / hours:.0f} mAh/h")
    if end:
        print(f"totals  {end['totals']}")

    print("\nstrongest APs:")
    for bssid, a in sorted(aps.items(), key=lambda kv: -kv[1]["best"])[:10]:
        print(f"  {a['best']:4d} dBm  {bssid}  ch{a['ch']:<3} {a['caps']:<44}  {a['ssid']!r}")


def cmd_wigle(rows, args):
    head = next((r for r in rows if r["t"] == "session"), {})
    dev = head.get("device", {})
    out = open(args.output, "w", encoding="utf-8") if args.output else sys.stdout
    out.write(
        f"WigleWifi-1.4,appRelease=rftoolkit-{dev.get('app', '?')},model={dev.get('model', '?')},"
        f"release={dev.get('release', '?')},device=watch,display=,board=,brand=samsung\n"
    )
    out.write("MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n")
    n = 0
    for r in rows:
        loc = r.get("loc")
        if not loc or r["t"] not in ("wifi", "ble"):
            continue
        lat, lon, acc = loc
        if r["t"] == "wifi":
            if r["rssi"] in (None, 0):
                continue
            ssid = (r["ssid"] or "").replace('"', "").strip('"')
            out.write(f'{r["bssid"]},"{ssid}",{wigle_auth(r["caps"])},{ts(r["ms"])},{r["ch"]},{r["rssi"]},{lat},{lon},0,{acc},WIFI\n')
        else:
            out.write(f'{r["addr"]},,Misc [LE],{ts(r["ms"])},0,{r["rssi"]},{lat},{lon},0,{acc},BLE\n')
        n += 1
    print(f"wrote {n} WiGLE rows", file=sys.stderr)


def cmd_geojson(rows, args):
    feats = []
    for r in rows:
        loc = r.get("loc")
        if r["t"] == "wifi" and loc:
            feats.append({
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [loc[1], loc[0]]},
                "properties": {"kind": "wifi", "bssid": r["bssid"], "ssid": r["ssid"], "rssi": r["rssi"], "caps": r["caps"], "time": ts(r["ms"])},
            })
        elif r["t"] == "loc":
            feats.append({
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [r["lon"], r["lat"]]},
                "properties": {"kind": "track", "acc": r["acc"], "time": ts(r["ms"])},
            })
    out = open(args.output, "w", encoding="utf-8") if args.output else sys.stdout
    json.dump({"type": "FeatureCollection", "features": feats}, out)
    print(f"wrote {len(feats)} features", file=sys.stderr)


SEV_ORDER = {"HIGH": 0, "MEDIUM": 1, "LOW": 2, "INFO": 3}


def cmd_findings(rows, _args):
    fs = [r for r in rows if r["t"] == "finding"]
    if not fs:
        print("no findings in this session (recorded by app >= Phase 2)")
        return
    by_type = collections.defaultdict(list)
    for f in fs:
        by_type[(f["sev"], f["type"])].append(f)
    for (sev, typ), items in sorted(by_type.items(), key=lambda kv: (SEV_ORDER.get(kv[0][0], 9), kv[0][1])):
        print(f"[{sev}] {typ}  ×{len(items)}")
        for f in sorted(items, key=lambda f: f["rssi"] if f["rssi"] is not None else -999, reverse=True)[:15]:
            loc = f.get("loc")
            where = f"  @{loc[0]:.5f},{loc[1]:.5f}" if loc else ""
            rssi = f"{f['rssi']:4d}" if f["rssi"] is not None else "   ?"
            print(f"    {rssi} dBm  {f.get('id', f.get('bssid'))}  {f.get('name', f.get('ssid'))!r:<28} {f['detail']}{where}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["summary", "wigle", "geojson", "findings"])
    ap.add_argument("file")
    ap.add_argument("-o", "--output")
    args = ap.parse_args()
    rows = load(args.file)
    {"summary": cmd_summary, "wigle": cmd_wigle, "geojson": cmd_geojson, "findings": cmd_findings}[args.command](rows, args)


if __name__ == "__main__":
    main()
