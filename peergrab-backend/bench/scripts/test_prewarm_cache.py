"""Safety tests for the bounded cache prewarm runner; no Docker or network calls."""
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import preflight
import prewarm_cache


PROJECT = "peergrab-bench-prewarmtest"
URL = "http://127.0.0.1:38080"


class PrewarmCacheTest(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {
            "PEERGRAB_BENCH_PROJECT": PROJECT,
            "PEERGRAB_TEST_DB_HOST": "127.0.0.1",
            "PEERGRAB_TEST_DB_PORT": "33307",
        })
        self.env.start()
        self.addCleanup(self.env.stop)

    def test_preflight_login_and_write_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ids.txt"
            path.write_text("42\n42\n43\n", encoding="utf-8")
            with patch.object(prewarm_cache.preflight, "check") as check, \
                 patch.object(prewarm_cache, "arbitrator_token", return_value="private-token"), \
                 patch.object(prewarm_cache, "post_json", return_value={
                     "requested": 2, "distinct": 2, "found": 2, "written": 2,
                     "missing": 0, "cacheHealthy": True, "elapsedMs": 3}), \
                 patch.dict(os.environ, {"PEERGRAB_BENCH_PROJECT": PROJECT}, clear=False):
                result = prewarm_cache.run(URL, prewarm_cache.load_ids(path))
            check.assert_called_once_with(PROJECT, URL, "127.0.0.1", "33307")
            self.assertEqual(2, result["written"])

    def test_id_file_rejects_invalid_and_unbounded_input(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ids.txt"
            path.write_text("42\nnope\n", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                prewarm_cache.load_ids(path)
            path.write_text("\n".join(["1"] * 1001), encoding="utf-8")
            with self.assertRaises(RuntimeError):
                prewarm_cache.load_ids(path)

    def test_incomplete_cache_write_fails_closed(self):
        with patch.object(prewarm_cache.preflight, "check"), \
             patch.object(prewarm_cache, "arbitrator_token", return_value="private-token"), \
             patch.object(prewarm_cache, "post_json", return_value={
                 "requested": 1, "distinct": 1, "found": 1, "written": 0,
                 "missing": 0, "cacheHealthy": False, "elapsedMs": 3}):
            with self.assertRaises(RuntimeError):
                prewarm_cache.run(URL, [42])


if __name__ == "__main__":
    unittest.main()
