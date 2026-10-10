#!/usr/bin/env python3
"""Tests the evaluator's accounting and rejection paths; observations here are test doubles."""
import unittest
from evaluate_binary import observe, simple_typo
from evaluate import evaluate


class EvaluatorTest(unittest.TestCase):
    def setUp(self):
        self.fixtures = [dict(id="a", split="test", original="teh", candidates=["teh", "the"], accepted=["the"], must_preserve=False),
                         dict(id="b", split="test", original="Zevran", candidates=["Zevran", "zebra"], accepted=["Zevran"], must_preserve=True)]
        self.observations = [dict(fixture_id=case["id"], system=system,
                                  ranked_candidates=case["candidates"], action="KEEP", candidate=None,
                                  latency_ms=1., inference_executed=False)
                             for system in ("baseline", "openjev") for case in self.fixtures]

    def test_simple_typo_requires_bounded_letters_and_one_edit(self):
        for original, candidate in (("teh", "the"), ("ideaa", "idea"), ("tge", "the")):
            self.assertTrue(simple_typo(original, candidate))
        for original, candidate in (("jsut", "jutting"), ("O’Neil", "oneil"), ("Zevran", "zebra"), ("url_id", "url")):
            self.assertFalse(simple_typo(original, candidate))

    def test_binary_join_requires_real_model_observation(self):
        generated = [dict(self.fixtures[0], baseline_candidate="the", generated_candidates=["teh", "the"],
                          baseline_ranking=["the", "teh"], baseline_latency_ms=1., context="GENERAL_WRITING",
                          input_kind="text", language="en", original_known=False, original_user=False)]
        with self.assertRaises(ValueError):
            observe(generated, [], "test", 1.5, "real-model")

    def test_protected_binary_join_never_infers(self):
        generated = [dict(self.fixtures[0], baseline_candidate="the", generated_candidates=["teh", "the"],
                          baseline_ranking=["the", "teh"], baseline_latency_ms=1., context="GENERAL_WRITING",
                          input_kind="password", language="en", original_known=False, original_user=False)]
        rows = observe(generated, [], "test", 1.5, "real-model")
        self.assertEqual("KEEP", rows[1]["action"])
        self.assertFalse(rows[1]["inference_executed"])

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

    def test_different_generated_slates_fail(self):
        self.observations[0]["generated_candidates"] = ["teh", "the", "ten"]
        with self.assertRaises(ValueError):
            evaluate(self.fixtures, self.observations, "test")

    def test_actual_generator_candidates_allowed(self):
        for i in (0, 2):
            self.observations[i].update(generated_candidates=["teh", "the", "ten"], ranked_candidates=["ten", "teh", "the"])
        self.assertEqual(2, evaluate(self.fixtures, self.observations, "test")["metrics"]["openjev"]["count"])

    def test_model_identity_required(self):
        self.observations[2]["inference_executed"] = True
        with self.assertRaises(ValueError):
            evaluate(self.fixtures, self.observations, "test")


if __name__ == "__main__":
    unittest.main()
