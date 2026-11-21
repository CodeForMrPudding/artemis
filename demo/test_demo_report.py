import csv
import importlib.util
import tempfile
import subprocess
import shutil
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("demo_report", Path(__file__).with_name("demo_report.py"))
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)


class DemoReportTest(unittest.TestCase):
    def test_nearest_rank_percentiles_use_hand_checked_tail(self):
        samples = list(range(1000, 0, -1))
        self.assertEqual(500, demo.percentile(samples, .50))
        self.assertEqual(950, demo.percentile(samples, .95))
        self.assertEqual(990, demo.percentile(samples, .99))
        self.assertEqual(4950, demo.percentile(list(range(1, 5001)), .99))

    def test_invalid_percentile_requests_fail(self):
        for values, p in [([], .99), ([1], 0), ([1], 1.1)]:
            with self.assertRaises(ValueError):
                demo.percentile(values, p)

    def test_small_workloads_do_not_claim_a_p99(self):
        summary = demo.summarize_latencies(self.rows(20), [self.workload(20)])
        self.assertFalse(summary["errors"])
        self.assertIsNone(summary["results"][0]["p99_ns"])
        self.assertEqual("insufficient_samples", summary["results"][0]["tail_status"])

    def test_complete_measurement_has_exact_percentiles(self):
        summary = demo.summarize_latencies(self.rows(1000), [self.workload(1000)])
        self.assertFalse(summary["errors"])
        self.assertEqual(990, summary["results"][0]["p99_ns"])

    def test_mismatched_outcomes_missing_workers_and_duplicates_are_not_hidden(self):
        rows = self.rows(1000)
        rows[0]["outcome"] = "INTERNAL"
        config = self.workload(1000)
        config["threads"] = "2"
        result = demo.summarize_latencies(rows + [rows[1].copy()], [config])
        messages = " ".join(result["errors"]).lower()
        self.assertIn("outcome", messages)
        self.assertIn("worker", messages)
        self.assertIn("duplicate", messages)
        self.assertEqual(1001, result["results"][0]["samples"])

    def test_malformed_measurement_rows_fail(self):
        for value in ["-1", "not-a-number"]:
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "latencies.csv"
                with path.open("w", newline="") as output:
                    writer = csv.writer(output)
                    writer.writerow(["workload", "phase", "worker", "sample", "latency_ns", "outcome"])
                    writer.writerow(["get.hit", "measurement", 0, 0, value, "OK"])
                with self.assertRaises(ValueError):
                    demo.load_latency_rows(path)

    def test_every_evidence_selector_must_match_non_skipped_passing_tests(self):
        requirement = {"id": "V01", "title": "Names", "group": "Validation", "mode": "RocksDB", "evidence": [
            {"class": "Cases", "pattern": "^valid", "min_count": 1},
            {"class": "Cases", "pattern": "^invalid", "min_count": 1}]}
        cases = [{"class": "Cases", "name": "valid", "status": "PASS"}]
        self.assertEqual("MISSING", demo.evaluate_requirements([requirement], cases)[0]["status"])
        cases.append({"class": "Cases", "name": "invalid", "status": "SKIP"})
        self.assertEqual("SKIP", demo.evaluate_requirements([requirement], cases)[0]["status"])
        cases[-1]["status"] = "FAIL"
        self.assertEqual("FAIL", demo.evaluate_requirements([requirement], cases)[0]["status"])
        cases[-1]["status"] = "PASS"
        self.assertEqual("PASS", demo.evaluate_requirements([requirement], cases)[0]["status"])

    def test_junit_failures_errors_and_skips_are_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "TEST-cases.xml").write_text('''<testsuite><testcase classname="Cases" name="ok" time="0.1"/>
              <testcase classname="Cases" name="failure"><failure message="wrong"/></testcase>
              <testcase classname="Cases" name="error"><error message="bad"/></testcase>
              <testcase classname="Cases" name="skip"><skipped/></testcase></testsuite>''')
            cases = demo.load_test_cases(Path(directory))
            self.assertEqual(["PASS", "FAIL", "FAIL", "SKIP"], [c["status"] for c in cases])

    def test_html_escapes_scenario_and_environment_text(self):
        report = {"status": "FAIL", "test_count": 1, "requirements": [], "cases": [
            {"class": "Cases", "name": "<script>alert(1)</script>", "status": "FAIL", "seconds": 0, "detail": "<bad>"}],
            "latency": {"results": [], "errors": []}, "metadata": {"os": "<img src=x>"}, "problems": []}
        html = demo.render_html(report)
        self.assertNotIn("<script>alert(1)</script>", html)
        self.assertIn("&lt;script&gt;", html)
        self.assertIn("&lt;img src=x&gt;", html)

    def test_early_stage_failures_never_import_stale_global_test_results(self):
        source = Path(__file__).parent
        for reporter_status in (17, 0):
            with self.subTest(reporter_status=reporter_status), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "demo").mkdir()
                old_results = root / "build/test-results/test"
                old_results.mkdir(parents=True)
                (old_results / "TEST-old.xml").write_text('<testsuite><testcase classname="Old" name="stale pass"/></testsuite>')
                (root / "demo/spec-coverage.json").write_text('{"requirements": []}')
                for name in ["full-demo.sh", "demo_report.py"]:
                    shutil.copy(source / name, root / "demo" / name)
                (root / "demo/test_demo_report.py").write_text(f'import sys; sys.exit({reporter_status})\n')
                wrapper = root / "gradlew"
                wrapper.write_text('#!/usr/bin/env bash\nexit 23\n')
                wrapper.chmod(0o755)
                process = subprocess.run(["bash", "demo/full-demo.sh"], cwd=root, capture_output=True, text=True, timeout=15)
                self.assertNotEqual(0, process.returncode)
                run = next((root / "build").glob("full-demo.*"))
                import json
                report = json.loads((run / "report.json").read_text())
                self.assertEqual("FAIL", report["status"])
                self.assertEqual(0, report["test_count"])

    @staticmethod
    def rows(count):
        return [{"workload": "get.hit", "phase": "measurement", "worker": 0, "sample": i,
                 "latency_ns": i + 1, "outcome": "OK"} for i in range(count)]

    @staticmethod
    def workload(count):
        return {"workload": "get.hit", "description": "Read", "threads": "1", "initial_vectors": "1000", "final_vectors": "1000",
                "batch_size": "1", "warmup_per_worker": "1000", "measured_per_worker": str(count), "expected_outcome": "OK"}


if __name__ == "__main__":
    unittest.main()
