#!/usr/bin/env python3
"""Join actual Android candidate exports with actual host binary ONNX outputs.

No model prediction is created here. Missing eligible model observations are errors.
Tune on --split tune; freeze --margin before evaluating --split test. This evaluates
synthetic dictionary-stage decisions, not complete editor transaction correctness.
"""
import argparse
import hashlib
import json
from pathlib import Path
from evaluate import evaluate, read_jsonl


def simple_typo(a, b):
    if not (3 <= len(a) <= 24 and 3 <= len(b) <= 24 and a.isascii() and b.isascii() and a.isalpha() and b.isalpha() and a.islower() and b.islower() and a != b):
        return False
    if len(a) == len(b):
        differences = [i for i, pair in enumerate(zip(a, b)) if pair[0] != pair[1]]
        return len(differences) == 1 or (len(differences) == 2 and differences[1] == differences[0] + 1 and a[differences[0]] == b[differences[1]] and a[differences[1]] == b[differences[0]])
    if abs(len(a) - len(b)) != 1:
        return False
    longer, shorter = (a, b) if len(a) > len(b) else (b, a)
    return any(longer[:i] + longer[i + 1:] == shorter for i in range(len(longer)))


def terminal_vowel_typo(original, candidate):
    return simple_typo(original, candidate) and original[:-1] == candidate and original[-1] in "aeiou" and original[-1] == original[-2]


def observe(generated, model_rows, split, margin, identity, replacement_family="simple"):

    actual = {(row["id"], row["candidate"]): row for row in model_rows if row["split"] == split}
    if len(actual) != sum(row["split"] == split for row in model_rows):
        raise ValueError("duplicate binary observations")
    output = []
    for fixture in generated:
        if fixture["split"] != split:
            continue
        original = fixture["original"]
        proposed = fixture["baseline_candidate"]
        slate = fixture["generated_candidates"]
        ranking = fixture["baseline_ranking"]
        allows_baseline = fixture["input_kind"] not in {"password", "pin", "number_decimal", "uri", "email"} and fixture["context"] != "CODE_OR_TERMINAL" and fixture["language"] == "en"
        baseline_replace = allows_baseline and original != proposed
        base = dict(fixture_id=fixture["id"], generated_candidates=slate, ranked_candidates=ranking,
                    measurement_scope="synthetic_dictionary_stage", latency_ms=fixture["baseline_latency_ms"])
        output.append(dict(base, system="baseline", action="REPLACE" if baseline_replace else "KEEP",
                           candidate=proposed if baseline_replace else None, inference_executed=False))
        eligible = (allows_baseline and fixture["input_kind"] != "incognito" and
                    fixture["context"] not in {"SEARCH", "FORM", "CODE_OR_TERMINAL"} and
                    not fixture["original_known"] and not fixture["original_user"] and simple_typo(original, proposed))
        action, chosen, ran, latency = "KEEP", None, False, 0.
        if eligible:
            key = (fixture["id"], proposed)
            if key not in actual:
                raise ValueError(f"missing actual model binary decision for fixture {fixture['id']}")
            row = actual[key]
            if row["original"] != original:
                raise ValueError("model input original differs from Android export")
            keep, correction = row["logits"]
            family_allows = replacement_family == "simple" or terminal_vowel_typo(original, proposed)
            action = "REPLACE" if family_allows and correction > 0 and correction - keep >= margin else "SUGGEST" if correction > 0 and correction > keep else "KEEP"
            chosen = proposed if action != "KEEP" else None
            ranking = ([proposed, original] if correction > keep else [original, proposed]) + [c for c in slate if c not in {original, proposed}]
            ran, latency = True, row["latency_ms"]
        output.append(dict(base, system="openjev", action=action, candidate=chosen, ranked_candidates=ranking,
                           inference_executed=ran, model_identity=identity if ran else None, latency_ms=latency))
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=Path(__file__).resolve().parents[2] / "src/androidTest/assets/openjev/fixtures.jsonl")
    parser.add_argument("--generated", type=Path, required=True)
    parser.add_argument("--model-observations", type=Path, required=True)
    parser.add_argument("--split", choices=("tune", "test"), required=True)
    parser.add_argument("--margin", type=float, required=True)
    parser.add_argument("--replacement-family", choices=("simple", "terminal-vowel"), default="simple")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not 0 < args.margin < float("inf"):
        parser.error("margin must be positive finite")
    generated = read_jsonl(args.generated)
    model = json.loads(args.model_observations.read_text())
    try:
        if model.get("fixture_sha256") != hashlib.sha256(args.fixtures.read_bytes()).hexdigest():
            raise ValueError("model observations must pin exact fixture content checksum")
        source = {row["id"]: row for row in read_jsonl(args.fixtures)}
        for fixture in generated:
            if fixture["id"] not in source or any(fixture.get(key) != value for key, value in source[fixture["id"]].items()):
                raise ValueError("Android fixture inputs differ from model reference inputs")
        observations = observe(generated, model["rows"], args.split, args.margin, model["model_identity"], args.replacement_family)
        report = evaluate(generated, observations, args.split)
    except (ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(json.dumps(row, allow_nan=False) + "\n" for row in observations))
    print(json.dumps(dict(report, margin=args.margin, replacement_family=args.replacement_family, model_sha256=model["model_sha256"]), indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
