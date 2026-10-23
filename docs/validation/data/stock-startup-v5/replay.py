#!/usr/bin/env python3
"""Offline replay of anonymized measured charging telemetry; Python stdlib + JDK 11+.

The fixtures contain measurements and retrospective truth; only measured telemetry
and eligible preceding journals are estimator inputs. Truth and saved estimates
are used to score/validate outputs, never fed to the estimator.
"""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import statistics
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
BASELINE = "0e427a1e5eae004662d49ecc462d6c81eb3b9b67"
SOURCES = ["ChargingEstimate", "ChargeSessionStore", "Ut5MaxStockChargeReference",
           "Ut5MaxStockVoltageReference", "Ut5MaxFastChargeReference",
           "ChargeTaperMatcher", "ChargeReferenceGate"]


def metrics(rows, estimate="full", truth="truth_full"):
    errors = [float(r[estimate]) - float(r[truth]) for r in rows
              if r[estimate] and r[truth] and float(r["soc"]) < 100]
    if not errors:
        return {"samples": 0}
    return {"samples": len(errors),
            "mae_minutes": statistics.mean(map(abs, errors)),
            "bias_minutes": statistics.mean(errors),
            "max_optimism_minutes": max(0, -min(errors)),
            "max_pessimism_minutes": max(0, max(errors)),
            "max_deadline_lateness_after_10min_buffer": max(0, -min(errors) - 10),
            "sample_instants_exceeding_10min_buffer": sum(e < -10 for e in errors)}


def summarize_a(rows):
    result = {}
    for case in dict.fromkeys(r["case"] for r in rows):
        result[case] = {}
        for mode in ["bundled", "runtime"]:
            selected = [r for r in rows if r["case"] == case and r["mode"] == mode]
            start = selected[0]
            first = {"soc": float(start["soc"]),
                     "full_minutes": float(start["full"]) if start["full"] else None,
                     "source": start["full_source"],
                     "actual_minutes": float(start["truth_full"]) if start["truth_full"] else None}
            if first["full_minutes"] is not None and first["actual_minutes"] is not None:
                first["error_minutes"] = first["full_minutes"] - first["actual_minutes"]
            result[case][mode] = {
                "first": first, "all": metrics(selected),
                "first30min": metrics([r for r in selected if int(r["physical_elapsed_ms"]) <= 1800000]),
                "below89": metrics([r for r in selected if float(r["soc"]) < 89]),
                "89plus": metrics([r for r in selected if float(r["soc"]) >= 89]),
                "limit80": metrics([r for r in selected if float(r["soc"]) < 80], "limit80", "truth80"),
                "limit95": metrics([r for r in selected if float(r["soc"]) < 95], "limit95", "truth95")}
    return result


def summarize_b(rows):
    start = rows[0]
    phases = {"all": rows,
              "first30min": [r for r in rows if int(r["elapsed_ms"]) <= 1800000],
              "below89": [r for r in rows if float(r["soc"]) < 89],
              "89plus": [r for r in rows if float(r["soc"]) >= 89]}
    first = {"soc": float(start["soc"]), "full_minutes": float(start["estimate"]),
             "actual_minutes": float(start["truth_full"]), "source": start["source"],
             "error_minutes": float(start["estimate"]) - float(start["truth_full"])}
    return {"first": first,
            **{key: metrics(value, "estimate") for key, value in phases.items()},
            "limit80": metrics([r for r in rows if float(r["soc"]) < 80], "limit80", "truth80"),
            "limit95": metrics([r for r in rows if float(r["soc"]) < 95], "limit95", "truth95")}


def run(label, revision, output):
    with tempfile.TemporaryDirectory(prefix="volt-charge-replay-") as temporary:
        build = Path(temporary)
        source_files, hashes = [], {}
        for name in SOURCES:
            relative = "src/local/navee/offlinelock/" + name + ".java"
            content = subprocess.check_output(["git", "show", revision + ":" + relative], cwd=REPO)
            source = build / (name + ".java")
            source.write_bytes(content)
            source_files.append(str(source))
            hashes[name] = hashlib.sha256(content).hexdigest()
        subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-d", str(build),
                        *source_files, str(HERE / "StartupReplay.java"),
                        str(HERE / "SecondScooterReplay.java")], check=True)
        results = {}
        for owner, main, arguments in [
            ("scooter-a", "StartupReplay", [str(HERE / "scooter-a"), str(build)]),
            ("scooter-b", "SecondScooterReplay", [str(HERE / "scooter-b/paired-input.csv")])]:
            raw = subprocess.check_output(["java", "-cp", str(build),
                                           "local.navee.offlinelock." + main, *arguments], text=True)
            filename = owner + "-" + label + ".csv"
            (output / filename).write_text(raw, encoding="utf-8")
            results[owner] = list(csv.DictReader(io.StringIO(raw)))
        return results, hashes


def validate(old, new):
    a0, a1 = old["scooter-a"], new["scooter-a"]
    b0, b1 = old["scooter-b"], new["scooter-b"]
    assert len(a0) == len(a1) == 11236
    assert len(b0) == len(b1) == 2077
    latest = [r for r in a0 if r["session"] == "9" and r["mode"] == "runtime"]
    assert len(latest) == 1254
    for r in latest:
        assert abs(float(r["saved_full"]) - float(r["full"])) <= 1e-9
        assert r["saved_source"] == r["full_source"]
    comparison = ["full", "full_source", "limit100", "full80", "limit80",
                  "full95", "limit95", "fullnull", "limitnull"]
    for before, after in zip(a0, a1):
        assert all(before[k] == after[k] for k in ["session", "point", "mode"])
        for field in ["limit80", "limit95"]:
            assert before[field] == after[field]
        if after["charger"] == "HIGH_SPEED" or float(after["soc"]) >= 89:
            assert all(before[k] == after[k] for k in comparison)
        assert after["full"] == after["limit100"] == after["full80"] == after["full95"] == after["fullnull"]
        assert not after["limitnull"]
        for target in [80, 95]:
            value = after["limit" + str(target)]
            if float(after["soc"]) >= target:
                assert value == "0.0"
            if value and after["full"]:
                assert float(value) <= float(after["full"]) + 1e-9
    for before, after in zip(b0, b1):
        assert before["elapsed_ms"] == after["elapsed_ms"]
        assert before["limit80"] == after["limit80"] and before["limit95"] == after["limit95"]
        if float(after["soc"]) >= 89:
            assert all(before[k] == after[k] for k in ["estimate", "source", "voltage_match"])
    return {"sample_counts": "pass", "saved_latest_1254_v4_estimates_and_sources": "pass",
            "80_and_95_percent_outputs_unchanged_both_scooters": "pass",
            "fast_and_stock89plus_unchanged": "pass", "full_target_invariants": "pass"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True, help="Directory for generated CSVs and summary.json")
    parser.add_argument("--candidate-ref", default="HEAD", help="Git commit/ref to compare with frozen v4; default HEAD")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    candidate = subprocess.check_output(["git", "rev-parse", args.candidate_ref], cwd=REPO, text=True).strip()
    output = {"baseline_commit": BASELINE, "candidate_commit": candidate, "source_sha256": {}, "results": {}}
    rows = {}
    for label, revision in [("baseline", BASELINE), ("candidate", candidate)]:
        rows[label], hashes = run(label, revision, args.out)
        output["source_sha256"][label] = hashes
        output["results"][label] = {"scooter-a": summarize_a(rows[label]["scooter-a"]),
                                    "scooter-b": summarize_b(rows[label]["scooter-b"])}
    output["checks"] = validate(rows["baseline"], rows["candidate"])
    expected = json.loads((HERE / "expected-results.json").read_text())
    assert output["source_sha256"] == expected["source_sha256"], "Source differs from published v4/v5 evidence"
    assert output["results"] == expected["results"], "Replay differs from published expected results"
    output["checks"]["published_expected_results"] = "pass"
    (args.out / "summary.json").write_text(json.dumps(output, indent=2) + "\n")
    print(json.dumps({"output_directory": str(args.out.resolve()), "checks": output["checks"]}, indent=2))


if __name__ == "__main__":
    main()
