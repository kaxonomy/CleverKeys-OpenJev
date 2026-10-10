#!/usr/bin/env python3
"""Tests the evaluator's accounting and rejection paths; observations here are test doubles."""
import unittest
from evaluate import evaluate


class EvaluatorTest(unittest.TestCase):
    def setUp(self):
        self.fixtures = [dict(id="a", split="test", original="teh", candidates=["teh", "the"], accepted=["the"], must_preserve=False),
                         dict(id="b", split="test", original="Zevran", candidates=["Zevran", "zebra"], accepted=["Zevran"], must_preserve=True)]
        self.observations = [dict(fixture_id=case["id"], system=system,
                                  ranked_candidates=case["candidates"], action="KEEP", candidate=None,
                                  latency_ms=1., inference_executed=False)
                             for system in ("baseline", "openjev") for case in self.fixtures]

    def test_false_replacements_count_and_zero_precision(self):
        self.observations[3].update(action="REPLACE", candidate="zebra")
        metrics = evaluate(self.fixtures, self.observations, "test")["metrics"]["openjev"]
        self.assertEqual(1, metrics["false_automatic_corrections"])
        self.assertEqual(0, metrics["correction_precision"])
        self.assertEqual(0, metrics["preservation_accuracy"])

    def test_no_inference_cannot_pass_neural_gate(self):
        report = evaluate(self.fixtures, self.observations, "test")
        self.assertFalse(report["synthetic_safety_gate_passed"])
        self.assertIsNone(report["metrics"]["openjev"]["correction_precision"])

    def test_missing_observations_fail(self):
        with self.assertRaises(ValueError):
            evaluate(self.fixtures, self.observations[:-1], "test")

    def test_nan_latency_fails(self):
        self.observations[0]["latency_ms"] = float("nan")
        with self.assertRaises(ValueError):
            evaluate(self.fixtures, self.observations, "test")

    def test_tuning_rows_excluded(self):
        self.fixtures.append(dict(self.fixtures[0], id="tune-a", split="tune"))
        self.assertEqual(2, evaluate(self.fixtures, self.observations, "test")["metrics"]["baseline"]["count"])

    def test_original_required_in_slate(self):
        self.observations[0]["ranked_candidates"] = ["the"]
        with self.assertRaises(ValueError):
            evaluate(self.fixtures, self.observations, "test")

    def test_scores_cannot_invent_candidates(self):
        self.observations[0]["ranked_candidates"] = ["teh", "not-a-candidate"]
        with self.assertRaises(ValueError):
            evaluate(self.fixtures, self.observations, "test")

    def test_model_identity_required(self):
        self.observations[2]["inference_executed"] = True
        with self.assertRaises(ValueError):
            evaluate(self.fixtures, self.observations, "test")


if __name__ == "__main__":
    unittest.main()
