#!/usr/bin/env python3
"""Opt-in Android WAV/AAC/MP3 upload experiments. No transcripts or credentials in outputs."""

import argparse
from decimal import Decimal
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[1]
VARIANTS = ["wav", "aac_32", "aac_64", "mp3_32", "mp3_64"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["stage", "prepare", "live", "stage-recordings", "prepare-recordings", "compare-recordings",
                                         "collect", "report", "report-recordings", "clean"])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--session", default="")
    args = parser.parse_args()
    if args.session and not re.fullmatch(r"[A-Za-z0-9_-]+", args.session):
        raise ValueError("Unsafe session identifier")
    suffix = "-" + args.session if args.session else ""
    output = ROOT / ".gradle/codec-lab"
    output.mkdir(parents=True, exist_ok=True)
    os.chmod(output, 0o700)

    def adb(*command, data=None):
        return subprocess.check_output(["adb", "-s", args.serial, *command], input=data)

    def stage(file, path):
        if not re.fullmatch(r"[A-Za-z0-9_./-]+", path):
            raise ValueError("Unsafe staging path")
        adb("shell", f'run-as com.notedown sh -c "umask 077; cat > {path}"', data=file.read_bytes())

    if args.phase in ("stage", "stage-recordings"):
        directory = "recording-inputs" if args.phase == "stage-recordings" else "inputs"
        adb("shell", "run-as", "com.notedown", "mkdir", "-p", "files/codec-lab/" + directory)
        generated = output / "generated-inputs"
        generated.mkdir(exist_ok=True)
        source = ROOT / "test-recordings/2026_11_11_07_30_08.mp3"
        for name, expression in {"stress_minus12": "volume=-12dB", "stress_minus24": "volume=-24dB",
                                 "stress_variable": r"volume=if(lt(mod(t\,4)\,2)\,0.063095734\,1):eval=frame"}.items():
            subprocess.run(["ffmpeg", "-v", "fatal", "-y", "-i", str(source), "-t", "20", "-af", expression,
                            "-c:a", "libmp3lame", "-b:a", "128k", str(generated / (name + ".mp3"))], check=True)
        files = sorted((ROOT / "test-recordings").glob("*.mp3")) + sorted(generated.glob("*.mp3"))
        if args.phase == "stage-recordings":
            recordings = output / "generated-recordings"
            recordings.mkdir(exist_ok=True)
            manifest = []
            for source in files:
                file = recordings / (source.stem + ".m4a")
                subprocess.run(["ffmpeg", "-v", "fatal", "-y", "-i", str(source), "-ar", "44100", "-ac", "1",
                                "-c:a", "aac", "-b:a", "96k", str(file)], check=True)
                info = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries",
                                                          "format=duration:stream=codec_name,sample_rate,channels", "-of", "json", str(file)]))
                stream = info["streams"][0]
                assert stream["codec_name"] == "aac" and int(stream["sample_rate"]) == 44100 and stream["channels"] == 1
                manifest.append(dict(file=file.name, source=source.name, duration_seconds=float(info["format"]["duration"]),
                                     codec=stream["codec_name"], sample_rate=int(stream["sample_rate"]), channels=stream["channels"],
                                     target_bitrate=96000, bytes=file.stat().st_size))
                stage(file, "files/codec-lab/recording-inputs/" + file.name)
            path = output / "recording-inputs.json"
            path.write_text(json.dumps(manifest, indent=2))
            stage(path, "files/codec-lab/recording-inputs.json")
        else:
            for file in files:
                stage(file, "files/codec-lab/inputs/" + file.name)
        budgets = [ROOT / ".gradle/silence-lab/budget.json", output / "budget.json"]
        seed = max((json.loads(p.read_text()) for p in budgets if p.exists()), key=lambda value: Decimal(value["reserved_usd"]),
                   default=dict(reserved_usd="0", reported_usd="0", requests=0))
        seed_path = output / ("budget-seed" + suffix + ".json")
        if not seed_path.exists():
            seed_path.write_text(json.dumps(seed))
        existing = subprocess.run(["adb", "-s", args.serial, "shell", "run-as", "com.notedown", "test", "-f", "files/codec-lab/budget.json"], capture_output=True)
        if existing.returncode != 0:
            adb("shell", 'run-as com.notedown sh -c "umask 077; cat > files/codec-lab/budget.json"', data=json.dumps(seed).encode())
        print("Staged", len(files), "private inputs with cumulative credit budget")
    elif args.phase in ("prepare", "live", "prepare-recordings", "compare-recordings"):
        paid = args.phase in ("live", "compare-recordings")
        if paid:
            ledger_prefix = "recording-results" if args.phase == "compare-recordings" else "live-results"
            if (output / (ledger_prefix + suffix + ".json")).exists():
                raise ValueError("Existing paid codec results; inspect before another explicitly authorized experiment")
            seed_path = output / ("budget-seed" + suffix + ".json")
            if not seed_path.exists():
                seed = adb("exec-out", "run-as", "com.notedown", "cat", "files/codec-lab/budget.json")
                json.loads(seed)
                seed_path.write_bytes(seed)
            match = re.search(r"(?m)^\s*OPENROUTER_API_KEY\s*=\s*(.*?)\s*$", (ROOT / ".env").read_text())
            if match is None:
                raise ValueError("Local test key not found")
            adb("shell", 'run-as com.notedown sh -c "umask 077; cat > files/codec-lab/key"', data=match[1].strip("\"'").encode())
        flag = {"prepare": "codecLab", "live": "codecLabLive", "prepare-recordings": "recordingCompare",
                "compare-recordings": "recordingCompareLive"}[args.phase]
        method = {"prepare": "prepare", "live": "live", "prepare-recordings": "prepareOriginalRecordings",
                  "compare-recordings": "compareOriginalRecordings"}[args.phase]
        selection = ["-e", "codecSession", args.session] if args.session else []
        try:
            result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
                         f"com.notedown.AudioCompressionLabTest#{method}", "-e", flag, "true",
                         *selection,
                         "com.notedown.test/androidx.test.runner.AndroidJUnitRunner").decode()
        finally:
            adb("shell", "run-as", "com.notedown", "rm", "-f", "files/codec-lab/key")
        print(result)
        if "OK (1 test)" not in result:
            raise RuntimeError("Codec experiment stopped; inspect budget/results before any paid restart")
    elif args.phase == "collect":
        for name in ["local-results" + suffix + ".json", "live-results" + suffix + ".json", "recording-inputs.json",
                     "recording-plan" + suffix + ".json", "recording-results" + suffix + ".json", "environment.json", "budget.json"]:
            result = subprocess.run(["adb", "-s", args.serial, "exec-out", "run-as", "com.notedown", "cat", "files/codec-lab/" + name], capture_output=True)
            if result.returncode != 0:
                continue
            try:
                json.loads(result.stdout)
            except json.JSONDecodeError:
                continue
            (output / name).write_bytes(result.stdout)
        print("Collected private metadata in", output)
    elif args.phase == "clean":
        adb("shell", "run-as", "com.notedown", "rm", "-rf", "files/codec-lab")
        shutil.rmtree(output / "generated-inputs", ignore_errors=True)
        shutil.rmtree(output / "generated-recordings", ignore_errors=True)
        print("Removed staged/generated audio; metadata and original recordings retained")
    elif args.phase == "report-recordings":
        plan = {row["file"]: row for row in json.loads((output / ("recording-plan" + suffix + ".json")).read_text())}
        live = json.loads((output / ("recording-results" + suffix + ".json")).read_text())
        names = sorted({row["file"] for row in live})
        summary = dict(files={}, aggregate={})
        for name in names:
            item = dict(plan[name], rates={})
            for rate in sorted({row["upload_limit_bps"] for row in live if row["file"] == name}):
                results = {}
                baseline = {r["iteration"]: r["total_ms"] for r in live if r["file"] == name and r["upload_limit_bps"] == rate and r["variant"] == "original"}
                for variant in ("original", "aac_64", "mp3_32"):
                    rows = [r for r in live if r["file"] == name and r["upload_limit_bps"] == rate and r["variant"] == variant]
                    metrics = {metric: statistics.median(r[metric] for r in rows) for metric in ("total_ms", "preparation_ms", "encoding_ms", "api_ms", "upload_ms", "bytes")}
                    metrics.update(rounds=len(rows), paired_delta_ms=statistics.median(r["total_ms"] - baseline[r["iteration"]] for r in rows),
                                   faster_rounds=sum(r["total_ms"] < baseline[r["iteration"]] for r in rows),
                                   word_edits=sorted({r["word_edits_vs_original"] for r in rows}),
                                   used_original=sum(r["reused_original"] for r in rows))
                    results[variant] = metrics
                item["rates"][str(rate)] = results
            summary["files"][name] = item
        for rate in ("0", "1000000"):
            selected = [item for item in summary["files"].values() if rate in item["rates"]]
            summary["aggregate"][rate] = {variant: dict(sum_per_file_median_ms=sum(item["rates"][rate][variant]["total_ms"] for item in selected),
                                                       wins_vs_original=sum(item["rates"][rate][variant]["total_ms"] < item["rates"][rate]["original"]["total_ms"] for item in selected),
                                                       fastest_files=sum(item["rates"][rate][variant]["total_ms"] == min(result["total_ms"] for result in item["rates"][rate].values()) for item in selected),
                                                       files=len(selected)) for variant in ("original", "aac_64", "mp3_32")}
        (output / ("recording-summary" + suffix + ".json")).write_text(json.dumps(summary, indent=2))
        print("Aggregate medians:", json.dumps(summary["aggregate"], indent=2))
        for name, item in summary["files"].items():
            print(json.dumps(dict(file=name, original_seconds=item["original_seconds"], shortened_seconds=item["shortened_seconds"],
                                  trimmed=item["trimmed"], rates={rate: {variant: round(values["total_ms"], 2) for variant, values in variants.items()}
                                                               for rate, variants in item["rates"].items()})))
        budget = json.loads((output / "budget.json").read_text())
        seed = json.loads((output / ("budget-seed" + suffix + ".json")).read_text())
        print("Cumulative budget:", json.dumps(budget))
        print("This comparison reported USD:", Decimal(budget["reported_usd"]) - Decimal(seed["reported_usd"]))
    else:
        local = json.loads((output / ("local-results" + suffix + ".json")).read_text())
        names = sorted({row["file"] for row in local})
        summary = {}
        for variant in VARIANTS:
            per_file = {}
            for name in names:
                rows = [r for r in local if r["variant"] == variant and r["file"] == name]
                ms = statistics.median(r["encoding_ms"] for r in rows)
                size = statistics.median(r["bytes"] for r in rows)
                wav_size = rows[0]["wav_bytes"]
                per_file[name] = dict(encoding_ms=ms, bytes=size, wav_bytes=wav_size,
                                      break_even_upload_bps=((wav_size - size) * 8000 / ms) if ms > 0 and variant != "wav" else None)
            summary[variant] = dict(aggregate_encoding_ms=sum(r["encoding_ms"] for r in per_file.values()),
                                    aggregate_bytes=sum(r["bytes"] for r in per_file.values()), per_file=per_file)
        live_file = output / ("live-results" + suffix + ".json")
        if live_file.exists():
            live = json.loads(live_file.read_text())
            selected = sorted({r["file"] for r in live if r["upload_limit_bps"] > 0})
            for variant in VARIANTS:
                quality = [r for r in live if r["variant"] == variant and r["upload_limit_bps"] == 0 and r["iteration"] == 0]
                summary[variant]["quality_vs_wav"] = dict(files=len(quality), reference_words=sum(r["reference_words"] for r in quality),
                                                         word_edits=sum(r["word_edits_vs_wav"] for r in quality), matches=sum(r["normalized_match"] for r in quality))
                summary[variant]["live_per_file"] = {
                    str(rate): {name: {metric: statistics.median(r[metric] for r in live if r["variant"] == variant and r["file"] == name and r["upload_limit_bps"] == rate)
                                      for metric in ["preparation_ms", "encoding_ms", "upload_ms", "api_ms", "total_ms"]}
                                for name in selected}
                    for rate in [0, 1000000]
                }
        (output / ("summary" + suffix + ".json")).write_text(json.dumps(summary, indent=2))
        for variant, data in summary.items():
            print(variant, json.dumps({k: v for k, v in data.items() if k != "per_file"}, indent=2))
        if (output / "budget.json").exists():
            budget = json.loads((output / "budget.json").read_text())
            seed_path = output / ("budget-seed" + suffix + ".json")
            seed = json.loads((seed_path if seed_path.exists() else output / "budget.json").read_text())
            print("Cumulative budget:", json.dumps(budget))
            print("This experiment reported USD:", Decimal(budget["reported_usd"]) - Decimal(seed["reported_usd"]))


if __name__ == "__main__":
    main()
