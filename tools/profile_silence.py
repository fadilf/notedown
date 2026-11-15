#!/usr/bin/env python3
"""Opt-in Android silence lab driver. Never prints credentials, audio, or transcripts."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess


ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["stage", "local", "containers", "live", "e2e", "collect", "report", "clean"])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--recordings", type=Path, default=ROOT / "test-recordings")
    parser.add_argument("--output", type=Path, default=ROOT / ".gradle/silence-lab")
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--budget-usd", default="5")
    parser.add_argument("--input-prefix", default="")
    parser.add_argument("--session", default="live-results")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    os.chmod(args.output, 0o700)

    def adb(*command, data=None):
        return subprocess.check_output(["adb", "-s", args.serial, *command], input=data)

    if args.phase == "stage":
        adb("shell", "run-as", "com.notedown", "mkdir", "-p", "files/silence-lab/inputs")
        for file in sorted(p for p in args.recordings.iterdir() if p.suffix in (".mp3", ".m4a")):
            if not re.fullmatch(r"[A-Za-z0-9_.-]+", file.name):
                raise ValueError("Input filenames must be safe shell identifiers")
            adb("shell", f'run-as com.notedown sh -c "cat > files/silence-lab/inputs/{file.name}"', data=file.read_bytes())
        print("Private recordings staged")
    elif args.phase in ("local", "containers", "live", "e2e"):
        if args.phase in ("live", "e2e"):
            match = re.search(r"(?m)^\s*OPENROUTER_API_KEY\s*=\s*(.*?)\s*$", (ROOT / ".env").read_text())
            if match is None:
                raise ValueError("Local OpenRouter test key not found")
            key = match[1].strip("\"'").encode()
            adb("shell", 'run-as com.notedown sh -c "umask 077; cat > files/silence-lab/key"', data=key)
        method = {"local": "benchmark", "containers": "containerFormats", "live": "liveComparisons", "e2e": "liveEndToEnd"}[args.phase]
        flag = "silenceLab" if args.phase in ("local", "containers") else "silenceLabLive"
        selection = ["-e", "inputPrefix", args.input_prefix] if args.input_prefix else []
        try:
            output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
                         f"com.notedown.SilenceOptimizationLabTest#{method}", "-e", flag, "true",
                         "-e", "repetitions", str(args.repetitions), "-e", "budgetUsd", args.budget_usd,
                         *selection, "-e", "ledgerName", args.session,
                         "com.notedown.test/androidx.test.runner.AndroidJUnitRunner").decode()
        finally:
            adb("shell", "run-as", "com.notedown", "rm", "-f", "files/silence-lab/key")
        print(output)
        if "OK (1 test)" not in output:
            raise RuntimeError("Profiling stopped; inspect the private ledger before any new paid run")
    elif args.phase == "collect":
        for name in ["results.json", "live-results.json", "quiet-results.json", "e2e-results.json", "container-results.json", "budget.json"]:
            result = subprocess.run(["adb", "-s", args.serial, "exec-out", "run-as", "com.notedown", "cat", f"files/silence-lab/{name}"], capture_output=True)
            if result.returncode == 0:
                try:
                    json.loads(result.stdout)
                except json.JSONDecodeError:
                    continue
                (args.output / name).write_bytes(result.stdout)
        print(f"Metadata collected in {args.output}")
    elif args.phase == "clean":
        adb("shell", "run-as", "com.notedown", "rm", "-rf", "files/silence-lab")
        for name in ("silence-lab-stress-inputs", "silence-lab-aac-inputs"):
            shutil.rmtree(ROOT / ".gradle" / name, ignore_errors=True)
        print("Removed staged/generated audio; retained metadata and original test recordings")
    else:
        local = json.loads((args.output / "results.json").read_text())
        rows = [row for row in local if row["kind"] == "local"]
        names = sorted({row["file"] for row in rows})
        variants = sorted({row["variant"] for row in rows})
        summary = {}
        for variant in variants:
            medians = {name: statistics.median(r["total_ms"] for r in rows if r["file"] == name and r["variant"] == variant) for name in names}
            baseline = {name: statistics.median(r["total_ms"] for r in rows if r["file"] == name and r["variant"] == "baseline") for name in names}
            summary[variant] = dict(corpus_ms=sum(medians.values()), median_speedup=statistics.median(baseline[n] / medians[n] for n in names),
                                    nonidentical_files=sorted({r["file"] for r in rows if r["variant"] == variant and not r["baseline_identical"]}),
                                    per_file_ms=medians)
        (args.output / "summary.json").write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary, indent=2))
        live = args.output / "live-results.json"
        if live.exists():
            print("Live comparison summary:")
            paid = json.loads(live.read_text())
            for variant in ["full", *variants]:
                selected = [r for r in paid if r["variant"] == variant]
                if not selected:
                    continue
                print(json.dumps(dict(variant=variant, reference_words=sum(r["reference_words"] for r in selected),
                                      result_words=sum(r["result_words"] for r in selected), word_edits=sum(r["word_edits_vs_full"] for r in selected),
                                      normalized_matches=sum(r["reference_normalized_match"] for r in selected),
                                      seconds=sum(r["audio_seconds"] for r in selected))))
            print((args.output / "budget.json").read_text())
        e2e = args.output / "e2e-results.json"
        if e2e.exists():
            rows = json.loads(e2e.read_text())
            result = {name: {variant: {metric: statistics.median(row[metric] for row in rows if row["file"] == name and row["variant"] == variant)
                                       for metric in ["total_ms", "preparation_ms", "api_ms"]}
                             for variant in ["original", "legacy_trimmed", "optimized_trimmed"]}
                      for name in sorted({row["file"] for row in rows})}
            (args.output / "e2e-summary.json").write_text(json.dumps(result, indent=2))
            print("End-to-end medians:")
            print(json.dumps(result, indent=2))
        containers = args.output / "container-results.json"
        if containers.exists():
            rows = json.loads(containers.read_text())
            names = sorted({row["file"] for row in rows})
            result = dict(files=len(names), paired_checks=len(rows), byte_identical=all(row["byte_identical"] for row in rows),
                          baseline_ms=sum(statistics.median(row["baseline_ms"] for row in rows if row["file"] == name) for name in names),
                          optimized_ms=sum(statistics.median(row["optimized_ms"] for row in rows if row["file"] == name) for name in names))
            (args.output / "container-summary.json").write_text(json.dumps(result, indent=2))
            print("AAC compatibility checks:", json.dumps(result))


if __name__ == "__main__":
    main()
