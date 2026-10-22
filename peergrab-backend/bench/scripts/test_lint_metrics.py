"""Offline checks for the benchmark wording linter."""

from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lint_metrics


BASE = "见论文。"


class LintMetricsTest(unittest.TestCase):
    def test_overclaims_are_flagged(self):
        text = "\n".join([
            "本次极限压测证明全站容量达到 SLA 99.9%。",
            "缓存提升 49% 吞吐，百万级用户无压力。",
            "项目已拆微服务并完成分库分表。",
            "生产环境 QPS 很高。",
        ])
        warnings = lint_metrics.lint_text(text, "report.md")
        names = " ".join(warnings)
        for rule in ("extreme-claim", "full-site-claim", "sla-claim",
                     "big-number", "cache-gain", "microservice", "sharding",
                     "production-traffic"):
            self.assertIn(rule, names)

    def test_zero_error_requires_denominator(self):
        without = lint_metrics.lint_text("500 RPS 采样零错误。", "a.md")
        with_denominator = lint_metrics.lint_text(
            "500 RPS×180s×3 轮共 269,608 次请求，0 错误。", "a.md")
        self.assertTrue(any("[zero-error]" in line for line in without))
        self.assertFalse(any("[zero-error]" in line for line in with_denominator))

    def test_percent_needs_baseline(self):
        bare = lint_metrics.lint_text("重构后 P99 下降 62%。", "a.md")
        with_baseline = lint_metrics.lint_text(
            "同工具同路径下 P99 由 45.7s 降到 17.3s，下降 62%。", "a.md")
        self.assertTrue(any("[percent-gain]" in line for line in bare))
        self.assertFalse(any("[percent-gain]" in line for line in with_baseline))

    def test_tps_and_qps_need_context(self):
        bare = lint_metrics.lint_text("结算 TPS 57，QPS 1200。", "a.md")
        contextual = lint_metrics.lint_text(
            "200 单短批次结算 TPS 57；单机单接口 1,200 QPS，60 秒窗口。", "a.md")
        self.assertTrue(any("[tps-context]" in line for line in bare))
        self.assertTrue(any("[qps-context]" in line for line in bare))
        self.assertFalse(any("[tps-context]" in line for line in contextual))
        self.assertFalse(any("[qps-context]" in line for line in contextual))

    def test_caveat_lines_and_code_fences_are_skipped(self):
        text = "\n".join([
            "> 不能写『极限压测』或 SLA；这是反例。",
            "```text",
            "500 RPS 零错误，QPS 百万级",
            "```",
            "正常句子。",
        ])
        warnings = lint_metrics.lint_text(text, "a.md")
        self.assertEqual([], warnings)

    def test_p99_unit_rule(self):
        bad = lint_metrics.lint_text("最差轮 P99 255。", "a.md")
        good = lint_metrics.lint_text("最差轮 P99 255 ms。", "a.md")
        self.assertTrue(any("[p99-unit]" in line for line in bad))
        self.assertFalse(any("[p99-unit]" in line for line in good))

    def test_directory_scan_and_missing_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "a.md").write_text("极限压测", encoding="utf-8")
            (root / "b.txt").write_text("极限压测", encoding="utf-8")
            warnings = []
            for path in lint_metrics.discover([root, root / "missing.md"]):
                if not path.exists():
                    warnings.append(f"{path}: does not exist")
                    continue
                warnings.extend(lint_metrics.lint_text(
                    path.read_text(encoding="utf-8"), str(path)))
        self.assertTrue(any("a.md" in warning for warning in warnings))
        self.assertFalse(any("b.txt" in warning for warning in warnings))
        self.assertTrue(any("missing.md" in warning for warning in warnings))

    def test_strict_exit_code(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "a.md"
            path.write_text("极限压测", encoding="utf-8")
            self.assertEqual(1, lint_metrics.main(["--strict", str(path)]))
            path.write_text("普通报告", encoding="utf-8")
            self.assertEqual(0, lint_metrics.main(["--strict", str(path)]))


if __name__ == "__main__":
    unittest.main()
