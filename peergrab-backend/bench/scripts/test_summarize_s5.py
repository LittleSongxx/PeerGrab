"""Pure accounting checks for S5 durable completion-rate reporting."""

from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from summarize_s5 import mysql, summarize


class S5SummaryTest(unittest.TestCase):
    def test_mysql_attaches_stdin_for_read_only_sql(self):
        with patch("summarize_s5.subprocess.run", return_value=Mock(returncode=0, stdout="1\n")) as run:
            self.assertEqual(mysql("a" * 64, "SELECT 1;"), "1")
        self.assertEqual(run.call_args.args[0][:4], ["docker", "exec", "-i", "a" * 64])
        self.assertEqual(run.call_args.kwargs["input"], "SELECT 1;")

    def test_counts_each_durable_transition_in_due_anchored_second(self):
        result = summarize("20260926-123456", "S5-mq", "PASS",
                           {"dueEpochMs": 100000, "expected": 4, "p99Ms": 2400},
                           [100050, 100950, 101100, 102400])
        self.assertEqual(result["completed"], 4)
        self.assertEqual(result["peakCompletionsInOneSecond"], 2)
        self.assertEqual(result["completedPerSecondFromDue"], [
            {"second": 0, "count": 2}, {"second": 1, "count": 1},
            {"second": 2, "count": 1},
        ])
        self.assertEqual(result["meanCompletedPerSecondFromDue"], 1.667)

    def test_missing_and_early_events_remain_visible(self):
        result = summarize("20260926-123456", "S5-fallback", "FAIL",
                           {"dueEpochMs": 100000, "expected": 3},
                           [99900, None, 101000])
        self.assertEqual(result["missing"], 1)
        self.assertEqual(result["completedPerSecondFromDue"], [
            {"second": -1, "count": 1}, {"second": 1, "count": 1},
        ])


if __name__ == "__main__":
    unittest.main()
