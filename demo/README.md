# Artemis demos

All demo launchers, example programs and report tooling live here. Run the shell commands from the repository root with JDK 21 selected.

| File | Purpose |
| --- | --- |
| `demo.sh` | Short insert/search/update/delete walkthrough and separate-process recovery. |
| `full-demo.sh` | Full spec checks, native/process checks, latency benchmark and reports. |
| `native-smoke.sh` | Native JNI/public-API checks, also invoked by Gradle's `nativeRocksDbSpike` task. |
| `CollectionApiDemo.java` | Source for the short walkthrough. |
| `LatencyBenchmark.java` | Reproducible real-RocksDB public-API latency workload. |
| `demo_report.py` | Coverage verification, percentile calculation and HTML/Markdown/JSON report generation. |
| `test_demo_report.py` | Reporting and measurement-integrity regression tests. |
| `spec-coverage.json` | Mapping from spec requirements to current-run verification evidence. |

```bash
bash demo/demo.sh
bash demo/full-demo.sh
```

The full demo requires GraalVM JDK 21 via `GRAALVM_HOME` or the workspace-local `.tools/` installation. Options include `--vectors`, `--dimensions`, `--warmup` and `--samples`; defaults and interpretation are in [the methodology](../docs/full-demo.md).

Generated databases, raw measurements, reports and logs remain in unique directories under `build/`. The latest recorded baseline is linked from [the progress log](../log/2026-11-20.md).
