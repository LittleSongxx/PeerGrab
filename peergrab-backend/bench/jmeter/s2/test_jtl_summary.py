"""Offline checks for the privacy boundary and JTL result accounting."""

import csv
from pathlib import Path
import tempfile
import unittest

import jtl_summary as jtl


class JtlSummaryTest(unittest.TestCase):
    def write(self, root, fields, rows):
        path = Path(root) / "phase.jtl"
        with path.open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
        return path

    def test_http_transport_and_business_failures_are_distinct(self):
        with tempfile.TemporaryDirectory() as root:
            fields = ["timeStamp", "elapsed", "label", "responseCode", "success", "bytes"]
            rows = [
                {"timeStamp": 1790592000000, "elapsed": 2, "label": "sample",
                 "responseCode": "200", "success": "true", "bytes": 10},
                {"timeStamp": 1790592000100, "elapsed": 4, "label": "sample",
                 "responseCode": "200", "success": "false", "bytes": 10},
                {"timeStamp": 1790592000200, "elapsed": 9, "label": "sample",
                 "responseCode": "500", "success": "false", "bytes": 10},
                {"timeStamp": 1790592000300, "elapsed": 12, "label": "sample",
                 "responseCode": "Non HTTP response code: private error text",
                 "success": "false", "bytes": 0},
            ]
            parsed = jtl.read_jtl(self.write(root, fields, rows), expected_labels={"sample"})
            result = jtl.summarize_rows(parsed, label="sample", duration_seconds=1,
                                        test_start_ms=1790592000000)
            self.assertEqual(result["samplesStarted"], 4)
            self.assertEqual(result["successfulSamples"], 1)
            self.assertEqual(result["nonHttpErrors"], 1)
            self.assertEqual(result["httpNon200"], 1)
            self.assertEqual(result["http200BusinessFailures"], 1)
            self.assertEqual(result["allP99Ms"], 12)
            self.assertEqual(result["startsPerSecond"], {0: 4})
            self.assertNotIn("private error text", str(result))

    def test_unsafe_columns_and_wrong_labels_are_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            fields = ["timeStamp", "elapsed", "label", "responseCode", "success", "url"]
            rows = [{"timeStamp": 1790592000000, "elapsed": 1, "label": "sample",
                     "responseCode": "200", "success": "true", "url": "secret"}]
            with self.assertRaisesRegex(jtl.JtlError, "unsafe"):
                jtl.read_jtl(self.write(root, fields, rows))
            fields.remove("url")
            rows[0].pop("url")
            with self.assertRaisesRegex(jtl.JtlError, "unexpected sampler"):
                jtl.read_jtl(self.write(root, fields, rows), expected_labels={"warmup"})

    def test_random_arrival_gate_allows_normal_variance(self):
        self.assertFalse(jtl.random_arrival_count_check(106_000, 600, 180)
                         ["grossUnderproduction"])
        self.assertTrue(jtl.random_arrival_count_check(100_000, 600, 180)
                        ["grossUnderproduction"])

    def test_per_second_pacing_detects_catch_up_burst(self):
        phase = {"startsPerSecond": {0: 200, 1: 195, 2: 450, 3: 155, 4: 0}}
        result = jtl.per_second_pacing_check(phase, 200)
        self.assertEqual(result["badSeconds"], [2, 4])


if __name__ == "__main__":
    unittest.main()
