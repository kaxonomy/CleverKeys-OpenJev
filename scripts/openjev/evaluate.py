#!/usr/bin/env python3
"""Compare observed baseline/OpenJev decisions on synthetic, split-isolated fixtures.

This consumes predictions produced by an actual harness; it never creates predictions.
Each JSONL observation has fixture_id, system (baseline/openjev), ranked_candidates,
action (KEEP/SUGGEST/REPLACE), candidate (null for KEEP), latency_ms and
inference_executed (bool). OpenJev inference rows must also carry model_identity.
Reports omit fixture text. Synthetic inputs are not a representative production corpus.
"""
import argparse
import json
import math
from pathlib import Path


def read_jsonl(path):
    with Path(path).open(encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def percentile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    position = (len(values) - 1) * fraction
    lo = math.floor(position)
    hi = math.ceil(position)
    return values[lo] + (values[hi] - values[lo]) * (position - lo)


def ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def validate(fixtures, observations, split):
    all_cases = {row["id"]: row for row in fixtures}
    if len(all_cases) != len(fixtures):
        raise ValueError("duplicate fixture IDs")
    cases = {key: row for key, row in all_cases.items() if row["split"] == split}
    if not cases:
        raise ValueError("empty selected split")
    rows = {"baseline": {}, "openjev": {}}
    for row in observations:
        case_id = row["fixture_id"]
        if case_id not in all_cases:
            raise ValueError("unknown fixture ID")
        if case_id not in cases:
            continue
        system = row["system"]
        if system not in rows or case_id in rows[system]:
            raise ValueError("unknown system or duplicate observation")
        if row["action"] not in {"KEEP", "SUGGEST", "REPLACE"}:
            raise ValueError("invalid action")
        ranked = row["ranked_candidates"]
        if not ranked or len(ranked) != len(set(ranked)):
            raise ValueError("empty or duplicate candidate ranking")
        slate = row.get("generated_candidates", cases[case_id]["candidates"])
        if not isinstance(slate, list) or not slate or len(slate) > 32 or any(not isinstance(c, str) or len(c) > 256 for c in slate):
            raise ValueError("invalid generated candidate slate")
        if any(candidate not in slate for candidate in ranked):
            raise ValueError("candidate outside declared/generated slate")
        if cases[case_id]["original"] not in ranked:
            raise ValueError("ranking must include explicit original KEEP candidate")
        if row["action"] != "KEEP" and row.get("candidate") not in ranked:
            raise ValueError("decision candidate absent from ranking")
        if row["action"] == "KEEP" and row.get("candidate") is not None:
            raise ValueError("KEEP candidate must be null")
        latency = row["latency_ms"]
        if isinstance(latency, bool) or not isinstance(latency, (int, float)) or not math.isfinite(latency) or latency < 0:
            raise ValueError("latency must be finite nonnegative measured milliseconds")
        if type(row["inference_executed"]) is not bool:
            raise ValueError("inference_executed must be boolean")
        if system == "openjev" and row["inference_executed"] and not row.get("model_identity"):
            raise ValueError("real model identity required for neural observations")
        rows[system][case_id] = row
    for system, records in rows.items():
        if set(records) != set(cases):
            raise ValueError(f"{system} requires one observation per selected fixture: {len(records)}/{len(cases)}")
    for case_id in cases:
        a = rows["baseline"][case_id].get("generated_candidates", cases[case_id]["candidates"])
        b = rows["openjev"][case_id].get("generated_candidates", cases[case_id]["candidates"])
        if a != b:
            raise ValueError("baseline and OpenJev must evaluate the same generated slate")
    return cases, rows


def metrics(cases, rows):
    counts = dict(top1_correct=0, false_automatic_corrections=0, correct_automatic_corrections=0,
                  automatic_corrections=0, preservation_cases=0, correctly_preserved=0,
                  correction_cases=0, useful_suggestions=0, suggestions=0, keep=0, inference_executed=0)
    for case_id, row in rows.items():
        case = cases[case_id]
        accepted = case["accepted"]
        preserve = case["must_preserve"]
        counts["top1_correct"] += row["ranked_candidates"][0] in accepted
        counts["preservation_cases"] += preserve
        counts["correction_cases"] += not preserve
        counts["inference_executed"] += row["inference_executed"]
        action = row["action"]
        if action == "KEEP":
            counts["keep"] += 1
        elif action == "SUGGEST":
            counts["suggestions"] += 1
            counts["useful_suggestions"] += row["candidate"] in accepted and row["candidate"] != case["original"]
        else:
            counts["automatic_corrections"] += 1
            correct = not preserve and row["candidate"] in accepted and row["candidate"] != case["original"]
            counts["correct_automatic_corrections"] += correct
            counts["false_automatic_corrections"] += not correct
        counts["correctly_preserved"] += preserve and (action != "REPLACE" or row["candidate"] == case["original"])
    n = len(rows)
    return {"count": n, **counts,
            "candidate_top1_accuracy": ratio(counts["top1_correct"], n),
            "false_automatic_correction_rate": ratio(counts["false_automatic_corrections"], n),
            "preservation_accuracy": ratio(counts["correctly_preserved"], counts["preservation_cases"]),
            "correction_precision": ratio(counts["correct_automatic_corrections"], counts["automatic_corrections"]),
            "correction_recall": ratio(counts["correct_automatic_corrections"], counts["correction_cases"]),
            "suggestion_usefulness_proxy": ratio(counts["useful_suggestions"], counts["suggestions"]),
            "keep_frequency": ratio(counts["keep"], n),
            "latency_p50_ms": percentile([r["latency_ms"] for r in rows.values()], .5),
            "latency_p95_ms": percentile([r["latency_ms"] for r in rows.values()], .95)}


def evaluate(fixtures, observations, split):
    cases, records = validate(fixtures, observations, split)
    result = {system: metrics(cases, rows) for system, rows in records.items()}
    has_neural = result["openjev"]["inference_executed"] > 0
    safe = (result["openjev"]["false_automatic_corrections"] == 0 and
            result["openjev"]["false_automatic_corrections"] <= result["baseline"]["false_automatic_corrections"])
    return {"split": split, "metrics": result,
            "model_identities": sorted({r["model_identity"] for r in records["openjev"].values() if r["inference_executed"]}),
            "synthetic_safety_gate_passed": safe and has_neural,
            "replacement_quality_gate_passed": split == "test" and safe and has_neural and result["openjev"]["correct_automatic_corrections"] > 0,
            "limitation": "Synthetic gate provides limited held-out evidence; raw logits remain uncalibrated and broader production quality is unmeasured."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=Path(__file__).resolve().parents[2] / "src/androidTest/assets/openjev/fixtures.jsonl")
    parser.add_argument("--observations", type=Path, required=True)
    parser.add_argument("--split", choices=("tune", "test"), default="test")
    parser.add_argument("--output", type=Path, help="Explicit final/scratch artifact path; default prints aggregate JSON")
    args = parser.parse_args()
    try:
        report = evaluate(read_jsonl(args.fixtures), read_jsonl(args.observations), args.split)
    except (ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
    output = json.dumps(report, indent=2, allow_nan=False) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    else:
        print(output, end="")


if __name__ == "__main__":
    main()
