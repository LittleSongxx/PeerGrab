"""Safety and orchestration tests; never run Docker or a real seed."""
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import preflight
import seed_bench


PROJECT = "peergrab-bench-seedtest"
URL = "http://127.0.0.1:38080"


class SeedBenchTest(unittest.TestCase):
    def setUp(self):
        self.events = []
        values = {"PEERGRAB_BENCH_PROJECT": PROJECT, "PEERGRAB_TEST_DB_HOST": "127.0.0.1",
                  "PEERGRAB_TEST_DB_PORT": "33307"}
        env = patch.dict(os.environ, values)
        env.start()
        self.addCleanup(env.stop)

    def test_s2_preflight_before_sql_and_deterministic_count(self):
        def check(*args):
            self.events.append("preflight")
            return {"mysql_container_id": "bench-mysql"}

        def mysql(_, sql):
            self.events.append("mysql")
            return PROJECT if "SELECT project_name" in sql else ""

        with patch.object(seed_bench.preflight, "check", side_effect=check), \
             patch.object(seed_bench, "mysql", side_effect=mysql), \
             patch.object(seed_bench, "scalar", side_effect=[0, 0, 0, 123]):
            result = seed_bench.seed("s2", 123, URL)
        self.assertEqual(self.events[0], "preflight")
        self.assertEqual(result["seeded"], 123)
        self.assertFalse(result["bloom_rebuilt"])

    def test_s3_rebuild_uses_arbitrator_after_seed(self):
        with patch.object(seed_bench.preflight, "check", return_value={"mysql_container_id": "bench-mysql"}), \
             patch.object(seed_bench, "mysql", side_effect=lambda _, sql: PROJECT if "SELECT project_name" in sql else ""), \
             patch.object(seed_bench, "scalar", side_effect=[0, 0, 0, 100]), \
             patch.object(seed_bench, "arbitrator_token", return_value="bench-token") as login, \
             patch.object(seed_bench, "post_json", return_value={"registered": 100}) as post:
            result = seed_bench.seed("s3", 10000, URL)
        login.assert_called_once_with(URL)
        post.assert_called_once_with(URL, "/api/internal/bloom-rebuild", {}, "bench-token")
        self.assertTrue(result["bloom_rebuilt"])

    def test_public_target_refused_before_sql(self):
        with patch.object(seed_bench.preflight, "check", side_effect=preflight.Refused("public URL")), \
             patch.object(seed_bench, "mysql") as mysql:
            with self.assertRaises(preflight.Refused):
                seed_bench.seed("s2", 10000, "https://www.peergrab.cn")
        mysql.assert_not_called()

    def test_nonempty_task_table_refused(self):
        with patch.object(seed_bench.preflight, "check", return_value={"mysql_container_id": "bench-mysql"}), \
             patch.object(seed_bench, "mysql", return_value=PROJECT), \
             patch.object(seed_bench, "scalar", side_effect=[0, 1]), \
             patch.object(seed_bench, "arbitrator_token") as login:
            with self.assertRaisesRegex(preflight.Refused, "empty task table"):
                seed_bench.seed("s3", 10000, URL)
        login.assert_not_called()


if __name__ == "__main__":
    unittest.main()
