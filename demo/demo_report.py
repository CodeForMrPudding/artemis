#!/usr/bin/env python3
"""Validate a full-demo run and render its actual evidence. Standard library only."""
import argparse
import collections
import csv
import datetime
import html
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def percentile(values, fraction):
    if not values or not 0 < fraction <= 1:
        raise ValueError("Percentile requires samples and 0 < fraction <= 1")
    return sorted(values)[math.ceil(fraction * len(values)) - 1]


def load_test_cases(directory):
    cases = []
    for path in sorted(Path(directory).glob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            issue = case.find("failure")
            if issue is None:
                issue = case.find("error")
            status = "FAIL" if issue is not None else "SKIP" if case.find("skipped") is not None else "PASS"
            cases.append({"class": case.get("classname", root.get("name", "")), "name": case.get("name", ""),
                          "status": status, "seconds": float(case.get("time", 0)),
                          "detail": "" if issue is None else issue.get("message", "") + "\n" + (issue.text or "")})
    return cases


def evaluate_requirements(requirements, cases):
    results = []
    for requirement in requirements:
        matched, statuses = [], []
        for selector in requirement["evidence"]:
            found = [case for case in cases if case["class"] == selector["class"] and re.search(selector["pattern"], case["name"])]
            if len(found) < selector.get("min_count", 1):
                statuses.append("MISSING")
            statuses.extend(case["status"] for case in found)
            matched.extend(found)
        status = next((s for s in ["FAIL", "MISSING", "SKIP"] if s in statuses), "PASS" if statuses else "MISSING")
        results.append({**requirement, "status": status, "matched": matched})
    return results


def load_latency_rows(path):
    with Path(path).open(newline="") as source:
        reader = csv.DictReader(source)
        expected = ["workload", "phase", "worker", "sample", "latency_ns", "outcome"]
        if reader.fieldnames != expected:
            raise ValueError("Unexpected latency CSV schema")
        rows = []
        for line, row in enumerate(reader, 2):
            try:
                for key in ["worker", "sample", "latency_ns"]:
                    row[key] = int(row[key])
                    if row[key] < 0:
                        raise ValueError("Negative " + key)
                if row["phase"] != "measurement" or not row["workload"] or not row["outcome"] or None in row:
                    raise ValueError("Incomplete or nonmeasurement row")
            except (ValueError, TypeError) as error:
                raise ValueError(f"Invalid latency CSV line {line}: {error}") from error
            rows.append(row)
        return rows


def summarize_latencies(rows, workloads, min_samples=1000):
    groups = collections.defaultdict(list)
    for row in rows:
        groups[row["workload"]].append(row)
    errors, results, configured = [], [], set()
    for workload in workloads:
        name = workload["workload"]
        if name in configured:
            errors.append(f"Duplicate workload declaration: {name}")
        configured.add(name)
        samples = groups.get(name, [])
        threads, expected = int(workload["threads"]), int(workload["measured_per_worker"])
        if threads < 1 or expected < 1:
            errors.append(f"{name}: nonpositive worker/sample configuration")
        workers = collections.defaultdict(list)
        for sample in samples:
            workers[sample["worker"]].append(sample)
        if len(workers) != threads:
            errors.append(f"{name}: expected {threads} workers, got {len(workers)}")
        for worker, entries in workers.items():
            indices = [entry["sample"] for entry in entries]
            if len(indices) != len(set(indices)):
                errors.append(f"{name}: duplicate sample IDs for worker {worker}")
            if set(indices) != set(range(expected)) or len(indices) != expected:
                errors.append(f"{name}: incomplete samples for worker {worker}")
        unexpected = [row for row in samples if row["outcome"] != workload["expected_outcome"]]
        if unexpected:
            errors.append(f"{name}: {len(unexpected)} unexpected outcome(s)")
        values = [row["latency_ns"] for row in samples]
        enough = len(values) >= min_samples
        results.append({**workload, "threads": threads, "samples": len(samples), "unexpected_outcomes": len(unexpected),
                        "p50_ns": percentile(values, .5) if values else None,
                        "p95_ns": percentile(values, .95) if values else None,
                        "p99_ns": percentile(values, .99) if enough else None,
                        "max_ns": max(values) if values else None,
                        "tail_status": "sampled" if enough else "insufficient_samples"})
    for unknown in groups.keys() - configured:
        errors.append("Undeclared workload: " + unknown)
    if not workloads:
        errors.append("No declared latency workloads")
    return {"results": results, "errors": errors}


def ms(nanoseconds):
    return "—" if nanoseconds is None else f"{nanoseconds / 1_000_000:.4f}"


def render_markdown(report):
    lines = ["# Full collection API demo", "", f"**Result: {report['status']}**", "",
             f"{report['test_count']} JUnit scenarios; {len(report['requirements'])} spec requirements mapped to current-run evidence.", "",
             "## Latency", "", "Times are milliseconds, measured around completed public API calls on real RocksDB with synced WAL writes. Warm-up, setup and report I/O are excluded. Closed-loop clients; no coordinated-omission correction. p99 is a nearest-rank observation, not an SLO or confidence bound.", "",
             "| Workload | Outcome | Samples | Callers | p50 ms | p95 ms | p99 ms | max ms |", "|---|---|---:|---:|---:|---:|---:|---:|"]
    for item in report["latency"]["results"]:
        lines.append(f"| {item['workload']} | {item['expected_outcome']} | {item['samples']} | {item['threads']} | {ms(item['p50_ns'])} | {ms(item['p95_ns'])} | {ms(item['p99_ns'])} | {ms(item['max_ns'])} |")
    lines += ["", "Fault-injection/recovery test durations are correctness evidence and are never used as latency distributions. p99 is suppressed below 1,000 measured calls.", "", "## Environment and workload", "", "```json", json.dumps(report["metadata"], indent=2), "```", "", "## Spec coverage", "", "| ID | Requirement | Evidence mode | Result | Checks |", "|---|---|---|---|---:|"]
    for item in report["requirements"]:
        lines.append(f"| {item['id']} | {item['title']} | {item['mode']} | {item['status']} | {len(item['matched'])} |")
    for item in report["requirements"]:
        lines += ["", f"### {item['id']} — {item['title']}", ""]
        for case in item["matched"]:
            lines.append(f"- {case['status']} `{case['class']}` — {case['name']}")
    lines += ["", "## All correctness scenarios", ""]
    for case in report["cases"]:
        lines.append(f"- {case['status']} `{case['class']}` — {case['name']}")
    if report["problems"]:
        lines += ["", "## Problems", ""] + ["- " + problem for problem in report["problems"]]
    lines += ["", "Artifacts: [raw latencies](latencies.csv), [workloads](workloads.csv), [metadata](metadata.json), [JSON report](report.json), [HTML report](report.html), and logs/test XML in this directory.", ""]
    return "\n".join(lines)


def render_html(report):
    esc = lambda value: html.escape(str(value), quote=True)
    latency_rows = []
    for item in report["latency"]["results"]:
        values = [item["workload"], item["expected_outcome"], item["samples"], item["threads"], ms(item["p50_ns"]), ms(item["p95_ns"]), ms(item["p99_ns"]), ms(item["max_ns"])]
        latency_rows.append("<tr>" + "".join("<td>" + esc(value) + "</td>" for value in values) + "</tr>")
    requirement_rows = []
    for item in report["requirements"]:
        evidence = "".join(f"<li><b>{esc(case['status'])}</b> {esc(case['class'])} — {esc(case['name'])}</li>" for case in item["matched"])
        requirement_rows.append(f"<tr><td>{esc(item['id'])}</td><td><details><summary>{esc(item['title'])}</summary><ul>{evidence}</ul></details></td><td>{esc(item['mode'])}</td><td>{esc(item['status'])}</td></tr>")
    case_rows = "".join(f"<tr><td>{esc(case['status'])}</td><td>{esc(case['class'])}</td><td>{esc(case['name'])}<pre>{esc(case.get('detail', ''))}</pre></td></tr>" for case in report["cases"])
    problems = "".join("<li>" + esc(problem) + "</li>" for problem in report["problems"])
    return f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Artemis full API demo</title>
<style>body{{font:16px/1.5 system-ui,sans-serif;margin:32px auto;padding:0 24px;max-width:1400px;color:#162332;background:#f6f8fa}}h1,h2{{line-height:1.2}}table{{border-collapse:collapse;width:100%;background:white;margin:16px 0}}th,td{{padding:9px 12px;border-bottom:1px solid #dce2e8;text-align:left;vertical-align:top}}th{{background:#e8eef4}}summary{{cursor:pointer}}pre{{white-space:pre-wrap;overflow-wrap:anywhere}}a{{color:#075ab5}}.scroll{{overflow-x:auto}}.result{{font-weight:700;padding:14px;background:white;border-left:5px solid #246b4a}}</style>
<h1>Artemis · full collection API demo</h1><p class="result">{esc(report['status'])} · {report['test_count']} JUnit scenarios · {len(report['requirements'])} mapped spec requirements</p>
<p>Real RocksDB · JVM · exact FlatIndex · sync/WAL enabled. This is a local measured baseline, not a production or native/HNSW latency claim.</p>
<h2>Observed latency</h2><p>Milliseconds around completed public API calls, excluding warm-up/setup/report I/O. Closed-loop callers; no coordinated-omission correction. Nearest-rank p99 is shown only for at least 1,000 measurements. Fault-injection and restart test durations are not latency percentiles.</p>
<div class="scroll"><table><tr><th>Workload</th><th>Expected outcome</th><th>Samples</th><th>Callers</th><th>p50 ms</th><th>p95 ms</th><th>p99 ms</th><th>max ms</th></tr>{''.join(latency_rows)}</table></div>
<details><summary>Environment, workload and methodology</summary><pre>{esc(json.dumps(report['metadata'], indent=2))}</pre></details>
<h2>Spec edge cases</h2><p>Expand a requirement to see the current-run checks supporting it. Evidence mode identifies real RocksDB scenarios and controlled fault injection.</p>
<table><tr><th>ID</th><th>Requirement and evidence</th><th>Mode</th><th>Result</th></tr>{''.join(requirement_rows)}</table>
<details><summary>All {report['test_count']} correctness scenarios and stage evidence</summary><table><tr><th>Result</th><th>Class</th><th>Scenario</th></tr>{case_rows}</table></details>
<ul>{problems}</ul><p><a href="latencies.csv">Raw latencies CSV</a> · <a href="workloads.csv">Workloads CSV</a> · <a href="metadata.json">Environment JSON</a> · <a href="report.json">Full report JSON</a> · <a href="report.md">Markdown</a> · <a href="logs/">Logs</a> · <a href="test-results/">Test XML</a></p></html>'''


def build_report(run_directory, test_results, coverage_path, spec_only=False):
    directory = Path(run_directory)
    cases = load_test_cases(test_results)
    test_count = len(cases)
    stages_path = directory / "stages.json"
    stages = json.loads(stages_path.read_text()) if stages_path.exists() else []
    cases.extend({"class": "demo.stages", "name": stage["name"], "status": stage["status"], "seconds": 0,
                  "detail": stage.get("command", "") + " (log: " + stage.get("log", "") + ")"} for stage in stages)
    requirements = evaluate_requirements(json.loads(Path(coverage_path).read_text())["requirements"], cases)
    problems = []
    if not test_count:
        problems.append("No current-run JUnit test evidence")
    problems.extend(f"{case['status']}: {case['class']} / {case['name']}" for case in cases if case["status"] != "PASS")
    problems.extend(f"Requirement {item['id']}: {item['status']}" for item in requirements if item["status"] != "PASS")
    latency, metadata = {"results": [], "errors": []}, {}
    if not spec_only:
        try:
            metadata = json.loads((directory / "metadata.json").read_text())
            if metadata.get("status") != "complete":
                raise ValueError("Benchmark did not finish successfully")
            if metadata.get("sync_writes") is not True or metadata.get("arrival_model") != "closed-loop":
                raise ValueError("Benchmark must declare synced writes and closed-loop arrival model")
            with (directory / "workloads.csv").open(newline="") as source:
                workloads = list(csv.DictReader(source))
            latency = summarize_latencies(load_latency_rows(directory / "latencies.csv"), workloads)
            problems.extend(latency["errors"])
            if any(item["tail_status"] != "sampled" for item in latency["results"]):
                problems.append("Insufficient samples for one or more workload p99 estimates")
        except (OSError, ValueError, KeyError, TypeError) as error:
            problems.append("Benchmark evidence invalid/incomplete: " + str(error))
    report = {"status": "FAIL" if problems else "CORRECTNESS_PASS_LATENCY_PENDING" if spec_only else "PASS",
              "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "test_count": test_count, "requirements": requirements, "cases": cases,
              "metadata": metadata, "latency": latency, "problems": problems}
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "report.json").write_text(json.dumps(report, indent=2))
    (directory / "report.md").write_text(render_markdown(report))
    (directory / "report.html").write_text(render_html(report))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_directory", type=Path)
    parser.add_argument("--test-results", type=Path, required=True)
    parser.add_argument("--coverage", type=Path, default=Path("demo/spec-coverage.json"))
    parser.add_argument("--spec-only", action="store_true")
    arguments = parser.parse_args()
    report = build_report(arguments.run_directory, arguments.test_results, arguments.coverage, arguments.spec_only)
    print(f"{report['status']}: {report['test_count']} tests, {len(report['requirements'])} spec requirements")
    for item in report["requirements"]:
        print(f"  {item['status']:7} {item['id']:5} {item['title']} [{item['mode']}]")
    if report["latency"]["results"]:
        print("\nWorkload                                      n       p50 ms     p95 ms     p99 ms")
        for item in report["latency"]["results"]:
            print(f"{item['workload']:42} {item['samples']:7} {ms(item['p50_ns']):>10} {ms(item['p95_ns']):>10} {ms(item['p99_ns']):>10}")
    for problem in report["problems"]:
        print("  ERROR: " + problem)
    print("\nReport: " + str((arguments.run_directory / "report.html").resolve()))
    return 1 if report["status"] == "FAIL" else 0


if __name__ == "__main__":
    sys.exit(main())
