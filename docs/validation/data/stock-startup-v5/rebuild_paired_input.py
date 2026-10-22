#!/usr/bin/env python3
"""Rebuild Scooter B's paired inputs from anonymous relative-time state events.

This is a hypothetical paired-input replay, not an assertion of BLE-frame
freshness. Sensor states are held until their next event or explicit unavailable.
"""
import csv
import io
from pathlib import Path

HERE = Path(__file__).resolve().parent


def rebuild():
    events = [(int(r["event_elapsed_ms"]), r["channel"], r["state"])
              for r in csv.DictReader((HERE / "scooter-b/state-events.csv").open())]
    events.sort()
    start = next(t for t, k, s in events if k == "charging" and s == "on")
    assert start == 0
    finish = next(t for t, k, s in events if t > start and k == "battery" and s == "100")
    crossings = {target: next(t for t, k, s in events if t >= start and k == "battery"
                             and s.isnumeric() and int(s) >= target) for target in [80, 95]}
    packets = []
    for event in events:
        if not packets or event[0] - packets[-1][0][0] > 5:
            packets.append([])
        packets[-1].append(event)
    state, records, gap = {}, [], True
    for packet in packets:
        t = packet[-1][0]
        keys = {x[1] for x in packet}
        for _, k, s in packet:
            state[k] = s
        if any(s == "unavailable" for _, k, s in packet):
            gap = True
        if t < start or t > finish + 5 or "voltage" not in keys:
            continue
        if state.get("charging") != "on":
            continue
        if any(state.get(k) in [None, "unavailable", "unknown"]
               for k in ["battery", "voltage", "current", "power"]):
            continue
        soc, v, a, w = (float(state[k]) for k in ["battery", "voltage", "current", "power"])
        if a >= -.05 or abs(v * a - w) > .2:
            continue
        temperature = state.get("temperature_unconfirmed_F", "")
        temperature_c = "" if temperature in ["", "unavailable", "unknown"] else (float(temperature) - 32) * 5 / 9
        records.append({"elapsed_ms": t - start, "soc": soc, "volts": v, "amps": a,
                        "power_w": w, "temperature_c_assuming_F": temperature_c, "gap": int(gap),
                        "truth_full": max(0, (finish - t) / 60000),
                        "truth80": max(0, (crossings[80] - t) / 60000) if soc < 80 else 0,
                        "truth95": max(0, (crossings[95] - t) / 60000) if soc < 95 else 0})
        gap = False
    sampled, gap = [], False
    for r in records:
        gap |= bool(r["gap"])
        if not sampled or r["elapsed_ms"] - sampled[-1]["elapsed_ms"] >= 10000 or r["soc"] == 100:
            r = dict(r)
            r["gap"] = int(gap or bool(sampled and r["elapsed_ms"] - sampled[-1]["elapsed_ms"] > 25000))
            sampled.append(r)
            gap = False
    return sampled


if __name__ == "__main__":
    expected = list(csv.DictReader((HERE / "scooter-b/paired-input.csv").open()))
    actual = rebuild()
    # Round-trip through csv to match the published textual representations.
    text = io.StringIO()
    writer = csv.DictWriter(text, fieldnames=list(actual[0]))
    writer.writeheader()
    writer.writerows(actual)
    assert list(csv.DictReader(io.StringIO(text.getvalue()))) == expected
    print("PASS: all 2,077 paired inputs reproduce from 19,628 anonymous state events.")
