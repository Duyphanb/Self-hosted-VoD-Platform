#!/usr/bin/env python3
"""Measure FFprobe and the ADR-010 D27 reference encode inside the worker image (#124).

Each probe and encode runs in a fresh container of the worker image, without network and with the
production worker CPU and memory limits (deploy/docker-compose.prod.yml). The encode is the argument list of
worker/test-support ReferenceHlsEncodeFfmpegTests.referenceArguments, the Issue 5.5 command line. Clips are
generated at run time with FFmpeg's lavfi sources at the D26 size caps (1920x1080): a high-entropy clip
(full-strength temporal noise) and a synthetic typical clip, each at several durations, and each encoded with
every requested WORKER_FFMPEG_THREADS value. The report records the probe times (including a probe of a
cap-length file), encode seconds per media second, the fixed overhead, output bytes per second, the temp-disk
peak and docker stats peaks, in measurement.json and summary.md.

Values for the 10800 s cap that come from shorter clips are labelled predicted. The report states where it
ran: on a GitHub-hosted runner it is runner evidence, not A1 VPS evidence, and no run is an NFR-002 baseline.
No credentials are involved; only the generated clips and their outputs are written, under a fresh work
directory removed at the end.
"""
import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path, PurePosixPath
import platform
import re
import secrets
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
LABEL = "Measurement of the ADR-010 D27 reference encode in the worker image (#124)"
CAP_SECONDS = 10800                      # WORKER_MAX_DURATION_SECONDS (ADR-010 D26)
CAP_SIZE = (1920, 1080)                  # WORKER_MAX_WIDTH x WORKER_MAX_HEIGHT, landscape
BOX = (1280, 720)                        # HLS_TARGET_HEIGHT=720 box, landscape (ADR-010 D27)
SEGMENT_SECONDS = 6                      # HLS_SEGMENT_SECONDS
MAX_STREAMS = 8                          # WORKER_MAX_STREAMS
OUTPUT_BUDGET_KBPS = 5000                # WORKER_OUTPUT_BUDGET_KBPS (ADR-010 D27)
DISK_RESERVE_MB = 5120                   # WORKER_DISK_RESERVE_MB (ADR-010 D27)
UPLOAD_CAP_BYTES = 2048 * 1024**2        # MAX_UPLOAD_SIZE_MB: the largest raw copy a job can download
DURATION_TOLERANCE_SECONDS = 1.0         # ADR-010 D27 EXTINF tolerance
FRAME_RATE = 30
AUDIO_KBPS = 192
PROBE_REPEATS = 3
PROBE_TIMEOUT_SECONDS = 300
GENERATE_TIMEOUT_SECONDS = 4 * 3600
WORK_RESERVE_BYTES = 1024**3
PLAYLIST = "master.m3u8"
SEGMENT_URI = re.compile(r"^segments/[A-Za-z0-9._-]+\.ts$")
ANSI_ESCAPE = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
CLIPS = {
    # Every pixel changes every frame: the worst case for x264, and VBV-bound output.
    "high-entropy": {
        "video": "color=c=gray:s={w}x{h}:r={fps}:d={d},noise=alls=100:allf=t+u",
        "audio": "anoisesrc=d={d}:c=white:r=48000:a=0.5",
        "video_kbps": 40000,
        "description": "gray plus full-strength uniform temporal noise, white-noise audio",
    },
    # Synthetic stand-in for ordinary footage: a moving test pattern with light grain. Not natural video.
    "typical": {
        "video": "testsrc2=s={w}x{h}:r={fps}:d={d},noise=alls=8:allf=t",
        "audio": "sine=f=440:r=48000:d={d}",
        "video_kbps": 8000,
        "description": "testsrc2 pattern plus light temporal grain, sine audio (synthetic, not natural video)",
    },
}
# The shell wrapper times the tool inside the container, so container start and stop are excluded, and
# reads the container's own cgroup files before the container exits: the kernel's high-water marks
# (memory.peak, memory.swap.peak, pids.peak), the applied limits, OOM events and CPU throttling.
# Only the wrapper writes the metrics file: the tool's stdout and stderr go to their own files, and the
# wrapper's own error output stays in the container log. A flat-keyed file's key is everything before the
# first space: a kernel built with CONFIG_SCHED_CORE (Ubuntu's) adds the dotted key
# core_sched.force_idle_usec to cpu.stat.
WRAPPER = r"""metrics=$1
shift
cgroup=/sys/fs/cgroup
start=$(date +%s%N)
"$@" >"$metrics.stdout" 2>"$metrics.stderr"
status=$?
end=$(date +%s%N)
{
  echo "start_ns=$start"
  echo "end_ns=$end"
  echo "exit_code=$status"
  for name in memory.peak memory.max memory.swap.peak memory.swap.max cpu.max pids.peak; do
    if [ -r "$cgroup/$name" ]; then echo "$name=$(cat "$cgroup/$name")"; fi
  done
  for name in memory.events cpu.stat; do
    if [ -r "$cgroup/$name" ]; then sed "s/^\([^ =][^ =]*\) /$name.\1=/" "$cgroup/$name"; fi
  done
} >"$metrics"
exit "$status"
"""
REQUIRED_METRICS = ("start_ns", "end_ns", "exit_code")
METRIC_KEY = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.]*")


class MeasurementError(RuntimeError):
    """A measurement step failed; the message names the step."""


def evidence():
    """What a report is evidence of. The A1 VM runs this script too, so a fixed runner label would be wrong."""
    if os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted":
        return "GitHub-hosted runner: runner evidence, not A1 VPS evidence"
    return "Not a GitHub-hosted runner: A1 VPS evidence only when run on the A1 VM; a local run is not evidence"


def reference_arguments(input_path, demuxer, hls, box_width, box_height, threads):
    """ReferenceHlsEncodeFfmpegTests.referenceArguments with WORKER_FFMPEG_THREADS = threads (container paths)."""
    hls = PurePosixPath(hls)
    return ["-nostdin", "-hide_banner", "-xerror", "-nostats", "-loglevel", "error",
            "-progress", "pipe:1", "-stats_period", "1",
            "-protocol_whitelist", "file", "-max_streams", str(MAX_STREAMS),
            "-f", demuxer, "-i", "file:" + str(input_path),
            "-map", "0:V:0", "-map", "0:a:0?", "-map_metadata", "-1", "-map_chapters", "-1",
            "-vf", f"scale=w=min({box_width}\\,iw):h=min({box_height}\\,ih)"
                   ":force_original_aspect_ratio=decrease:force_divisible_by=2,"
                   "setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709",
            "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-preset", "medium", "-crf", "23",
            "-maxrate:v", "4000k", "-bufsize:v", "8000k",
            "-fpsmax", "60", "-force_key_frames", f"expr:gte(t,n_forced*{SEGMENT_SECONDS})",
            "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
            "-c:a", "aac", "-profile:a", "aac_low", "-b:a", "128k", "-ac", "2", "-ar", "48000",
            "-threads", str(threads), "-filter_threads", str(threads),
            "-f", "hls", "-hls_time", str(SEGMENT_SECONDS), "-hls_playlist_type", "vod",
            "-hls_list_size", "0", "-hls_segment_type", "mpegts", "-hls_flags", "independent_segments",
            "-hls_base_url", "segments/", "-hls_segment_filename", str(hls / "segments/seg_%05d.ts"),
            str(hls / PLAYLIST)]


def probe_arguments(input_path, demuxer):
    """MediaTools.probe plus the D26 -max_streams; Issue 5.4 adds its -codec_whitelist later."""
    return ["-hide_banner", "-loglevel", "error", "-protocol_whitelist", "file", "-max_streams", str(MAX_STREAMS),
            "-f", demuxer, "-show_format", "-show_streams", "-of", "json", "file:" + str(input_path)]


def source_arguments(kind, seconds, output, video_kbps=None):
    """Generates a 1920x1080 30 fps H.264 High / AAC stereo MP4, as a camera or export would."""
    clip = CLIPS[kind]
    values = {"w": CAP_SIZE[0], "h": CAP_SIZE[1], "fps": FRAME_RATE, "d": seconds}
    kbps = video_kbps or clip["video_kbps"]
    return ["-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", clip["video"].format(**values), "-f", "lavfi", "-i", clip["audio"].format(**values),
            "-map", "0:v", "-map", "1:a", "-t", str(seconds),
            "-c:v", "libx264", "-preset", "superfast", "-profile:v", "high", "-pix_fmt", "yuv420p",
            "-b:v", f"{kbps}k", "-maxrate:v", f"{kbps}k", "-bufsize:v", f"{2 * kbps}k",
            "-c:a", "aac", "-b:a", f"{AUDIO_KBPS}k", "-ac", "2", "-ar", "48000",
            "-movflags", "+faststart", str(output)]


def long_clip_video_kbps(seconds):
    """A long clip must still fit the upload cap, as a real upload of that length would."""
    whole_kbps = UPLOAD_CAP_BYTES * 8 / seconds / 1000
    return max(100, min(CLIPS["high-entropy"]["video_kbps"], math.floor(whole_kbps * 0.97) - AUDIO_KBPS))


def parse_seconds_list(text, minimum_count=1):
    values = []
    for item in re.split(r"[\s,]+", text.strip()):
        if not re.fullmatch(r"[1-9][0-9]*", item or ""):
            raise argparse.ArgumentTypeError("durations and thread counts are positive whole numbers")
        values.append(int(item))
    if len(set(values)) < minimum_count:
        raise argparse.ArgumentTypeError(f"give at least {minimum_count} distinct values")
    return sorted(set(values))


def clip_seconds(text):
    values = parse_seconds_list(text, minimum_count=2)  # two durations give the fixed overhead
    if values[-1] > CAP_SECONDS:
        raise argparse.ArgumentTypeError(f"clips cannot exceed the {CAP_SECONDS} s cap")
    return values


def thread_counts(text):
    values = parse_seconds_list(text)
    if values[-1] > 16:
        raise argparse.ArgumentTypeError("WORKER_FFMPEG_THREADS is an integer from 1 to 16")
    return values


def long_seconds(text):
    if text.strip() in ("", "0"):
        return 0
    value = parse_seconds_list(text)
    if len(value) != 1 or value[0] > CAP_SECONDS:
        raise argparse.ArgumentTypeError(f"one duration up to {CAP_SECONDS} s, or 0 for none")
    return value[0]


def long_threads(text):
    """The long clip is encoded once: a cap-length encode takes hours, so one thread setting is measured."""
    value = thread_counts(text)
    if len(value) != 1:
        raise argparse.ArgumentTypeError("one WORKER_FFMPEG_THREADS value for the long clip")
    return value[0]


def worker_limits(overlay_text):
    """The worker's deploy.resources.limits from the production overlay."""
    block = re.search(r"^  worker:\n((?:    .*\n|\n)*)", overlay_text, re.M)
    limits = block and re.search(r'^        limits:\n          cpus: "([0-9.]+)"\n          memory: ([0-9]+)M$',
                                 block.group(1), re.M)
    if not limits:
        raise MeasurementError("Cannot read the worker limits from deploy/docker-compose.prod.yml")
    return {"cpus": limits.group(1), "memory_mib": int(limits.group(2))}


def linear_fit(points):
    """Least squares y = intercept + slope * x over (x, y) points with at least two distinct x."""
    if len({x for x, _ in points}) < 2:
        raise MeasurementError("A fit needs at least two distinct durations")
    count = len(points)
    mean_x = sum(x for x, _ in points) / count
    mean_y = sum(y for _, y in points) / count
    slope = (sum((x - mean_x) * (y - mean_y) for x, y in points)
             / sum((x - mean_x) ** 2 for x, _ in points))
    return {"slope": slope, "intercept": mean_y - slope * mean_x}


def size_bytes(text):
    match = re.fullmatch(r"([0-9.]+)\s*(B|kB|KB|MB|GB|TB|KiB|MiB|GiB|TiB)", text.strip())
    if not match:
        raise MeasurementError("Unrecognized docker stats size")
    units = {"B": 1, "kB": 1000, "KB": 1000, "MB": 1000**2, "GB": 1000**3, "TB": 1000**4,
             "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3, "TiB": 1024**4}
    return int(float(match.group(1)) * units[match.group(2)])


def parse_stats_line(line):
    """One `docker stats --format '{{json .}}'` row; None while the container has no sample yet."""
    text = ANSI_ESCAPE.sub("", line).strip()
    if not text.startswith("{"):
        return None
    row = json.loads(text)
    if "--" in (row.get("CPUPerc"), row.get("MemUsage")) or not row.get("MemUsage"):
        return None
    used, limit = row["MemUsage"].split("/", 1)
    if size_bytes(limit) == 0:  # The container has stopped: "0B / 0B".
        return None
    pids = row.get("PIDs", "")
    return {"cpu_percent": float(row["CPUPerc"].rstrip("%")), "memory_usage_bytes": size_bytes(used),
            "memory_limit_bytes": size_bytes(limit), "pids": int(pids) if pids.isdigit() else None}


def shown(text, limit=120):
    """A metrics line or value for an error message: quoted, escaped and truncated."""
    return repr(text if len(text) <= limit else text[:limit] + "...")


def parse_metrics(text):
    """The wrapper's key=value lines: timing, exit status and the container's cgroup counters.

    Blank lines are ignored; any other line must be one well-formed, unrepeated key=value, and an error
    names the offending line so a CI log shows what the kernel or wrapper printed.
    """
    values = {}
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        key, separator, value = line.partition("=")
        if not separator or not METRIC_KEY.fullmatch(key):
            raise MeasurementError(f"Malformed wrapper metrics line {number}: {shown(line)}")
        if key in values:
            raise MeasurementError(f"Repeated wrapper metric on line {number}: {shown(line)}")
        values[key] = value
    for key in REQUIRED_METRICS:
        if key not in values:
            raise MeasurementError(f"Incomplete wrapper metrics: no {key}")
        if not re.fullmatch(r"[0-9]+", values[key]):
            raise MeasurementError(f"Incomplete wrapper metrics: {key} is {shown(values[key])}")
    start, end = int(values["start_ns"]), int(values["end_ns"])
    if end < start:
        raise MeasurementError(f"Wrapper clock went backwards: start_ns={start}, end_ns={end}")
    cgroup = {}
    for key, value in values.items():
        if key in REQUIRED_METRICS:
            continue
        cgroup[key] = int(value) if re.fullmatch(r"[0-9]+", value) else value
    return {"seconds": (end - start) / 1e9, "exit_code": int(values["exit_code"]), "cgroup": cgroup}


def parse_playlist(text):
    lines = [line.strip() for line in text.splitlines()]
    if not lines or lines[0] != "#EXTM3U":
        raise MeasurementError("Output playlist does not start with #EXTM3U")
    segments, pending, playlist_type, end_list = [], None, None, False
    for line in lines[1:]:
        if line.startswith("#EXTINF:"):
            pending = float(line[len("#EXTINF:"):].split(",", 1)[0])
        elif line.startswith("#EXT-X-PLAYLIST-TYPE:"):
            playlist_type = line.split(":", 1)[1]
        elif line == "#EXT-X-ENDLIST":
            end_list = True
        elif line and not line.startswith("#"):
            if pending is None:
                raise MeasurementError("Output playlist has a URI without #EXTINF")
            segments.append((pending, line))
            pending = None
    return {"type": playlist_type, "end_list": end_list, "segments": segments}


def output_check(hls, media_seconds):
    """The ADR-010 D27 checks, so a timing is only reported for a complete, valid HLS output."""
    playlist_file = hls / PLAYLIST
    if not playlist_file.is_file():
        return ["missing " + PLAYLIST], 0, 0, 0.0
    playlist = parse_playlist(playlist_file.read_text(encoding="utf-8"))
    violations = []
    if playlist["type"] != "VOD":
        violations.append("playlist type is not VOD")
    if not playlist["end_list"]:
        violations.append("missing #EXT-X-ENDLIST")
    if not playlist["segments"]:
        violations.append("no segments")
    referenced = set()
    for _, uri in playlist["segments"]:
        segment = hls / uri
        if not SEGMENT_URI.match(uri) or not segment.is_file() or segment.stat().st_size == 0:
            violations.append("missing, empty or misnamed segment " + uri)
        referenced.add(segment.resolve())
    files = [path for path in hls.rglob("*") if path.is_file()]
    if any(path.resolve() not in referenced and path != playlist_file for path in files):
        violations.append("unreferenced file in the output")
    total = sum(duration for duration, _ in playlist["segments"])
    if abs(total - media_seconds) > DURATION_TOLERANCE_SECONDS:
        violations.append(f"EXTINF sum {total:.3f} s differs from the media duration {media_seconds:.3f} s")
    return violations, sum(path.stat().st_size for path in files), len(playlist["segments"]), total


def media_duration(probe):
    """ADR-010 D26: the first non-attached-picture video stream's duration, else the format duration."""
    for stream in probe.get("streams", []):
        if stream.get("codec_type") == "video" and stream.get("disposition", {}).get("attached_pic", 0) == 0:
            duration = stream.get("duration") or probe.get("format", {}).get("duration")
            return float(duration)
    raise MeasurementError("Generated clip has no video stream")


def allocated_bytes(path):
    total = 0
    for root, _, files in os.walk(path):
        for name in files:
            try:
                stat = os.lstat(os.path.join(root, name))
            except FileNotFoundError:
                continue
            total += stat.st_blocks * 512 if hasattr(stat, "st_blocks") else stat.st_size
    return total


def summarize_stats(samples):
    if not samples:
        return {"samples": 0}
    cpu = [sample["cpu_percent"] for sample in samples]
    pids = [sample["pids"] for sample in samples if sample["pids"] is not None]
    return {"samples": len(samples), "cpu_percent_peak": max(cpu), "cpu_percent_mean": sum(cpu) / len(cpu),
            "memory_usage_peak_bytes": max(sample["memory_usage_bytes"] for sample in samples),
            "memory_limit_bytes": max(sample["memory_limit_bytes"] for sample in samples),
            "pids_peak": max(pids) if pids else None,
            "note": "Sampled about once a second by docker stats; may miss shorter peaks. MemUsage is the cgroup "
                    "usage minus inactive file cache. The cgroup memory.peak is the kernel's high-water mark of the "
                    "whole usage, page cache included, so it can exceed what the processes need."}


def d27_budget_bytes(media_seconds):
    """ADR-010 D27: B = ceil(D x WORKER_OUTPUT_BUDGET_KBPS x 1000 / 8) + 16 MiB."""
    return math.ceil(media_seconds * OUTPUT_BUDGET_KBPS * 1000 / 8) + 16 * 1024**2


def derive(report):
    """Measured maxima, per-configuration fits and their labelled extrapolation to the 10800 s cap.

    Output bytes are fitted against duration too: the VBV buffer lets a clip's first seconds exceed the
    maxrate, so a short clip's bytes per second overstate the steady rate of a long encode.
    """
    runs = [run for run in report["runs"] if run.get("valid")]
    measured = {
        "worst_probe_seconds": max((probe["worst_seconds"] for probe in report["probes"]), default=None),
        "worst_probe_at_cap_seconds": max((probe["worst_seconds"] for probe in report["probes"]
                                           if probe["media_seconds"] >= CAP_SECONDS - 1), default=None),
        "max_encode_seconds_per_media_second": max((run["encode_seconds_per_media_second"] for run in runs),
                                                   default=None),
        "max_output_kbps": max((run["output"]["kbps"] for run in runs), default=None),
        "max_temp_disk_peak_bytes": max((run["temp_disk"]["peak_bytes"] for run in runs), default=None),
        "max_docker_stats_memory_bytes": max((run["docker_stats"].get("memory_usage_peak_bytes", 0) for run in runs),
                                             default=None),
        "max_cgroup_memory_peak_bytes": max((run["cgroup"].get("memory.peak", 0) for run in runs), default=None),
        "max_pids": max((run["docker_stats"].get("pids_peak") or 0 for run in runs), default=None),
    }
    fits, predicted = [], []
    for kind in sorted({run["kind"] for run in runs}):
        for threads in sorted({run["threads"] for run in runs if run["kind"] == kind}):
            subset = [run for run in runs if run["kind"] == kind and run["threads"] == threads and not run["long"]]
            if len({run["media_seconds"] for run in subset}) < 2:
                continue
            encode = linear_fit([(run["media_seconds"], run["encode_seconds"]) for run in subset])
            output = linear_fit([(run["media_seconds"], run["output"]["bytes"]) for run in subset])
            fits.append({"kind": kind, "threads": threads, "media_seconds": sorted(run["media_seconds"] for run in subset),
                         "encode_seconds_per_media_second": encode["slope"], "fixed_overhead_seconds": encode["intercept"],
                         "output_bytes_per_media_second": output["slope"], "output_fixed_bytes": output["intercept"]})
            predicted.append({"kind": kind, "threads": threads,
                              "encode_seconds": encode["intercept"] + encode["slope"] * CAP_SECONDS,
                              "output_bytes": output["intercept"] + output["slope"] * CAP_SECONDS})
    output_at_cap = max((item["output_bytes"] for item in predicted), default=None)
    budget = d27_budget_bytes(CAP_SECONDS)
    prediction = {
        "label": f"PREDICTED: linear extrapolation to {CAP_SECONDS} s from the shorter clips; not measured",
        "encode_seconds": predicted,
        "output_bytes": output_at_cap,
        "temp_disk_peak_bytes": None if output_at_cap is None else UPLOAD_CAP_BYTES + output_at_cap,
        "temp_disk_note": "Raw copy at the 2048 MiB upload cap plus the HLS output; they coexist until FFmpeg exits",
        "d27_budget_bytes": budget,
        "d27_free_bytes_required_after_probe": DISK_RESERVE_MB * 1024**2 + max(budget, 2 * budget - UPLOAD_CAP_BYTES),
    }
    return {"measured": measured, "fits": fits, "predicted_at_cap": prediction,
            "record": record_rows(report["probes"], runs, fits, predicted)}


def run_peaks(runs):
    """The largest peaks over a set of valid runs; None where no run has a sample."""
    def largest(values):
        return max((value for value in values if isinstance(value, (int, float))), default=None)
    return {"temp_disk_peak_bytes": largest(run["temp_disk"]["peak_bytes"] for run in runs),
            "cpu_percent_peak": largest(run["docker_stats"].get("cpu_percent_peak") for run in runs),
            "memory_usage_peak_bytes": largest(run["docker_stats"].get("memory_usage_peak_bytes") for run in runs),
            "cgroup_memory_peak_bytes": largest(run["cgroup"].get("memory.peak") for run in runs),
            "pids_peak": largest(run["docker_stats"].get("pids_peak") for run in runs)}


def record_rows(probes, runs, fits, predicted):
    """The rows of the docs/testing/WORKER-ENCODE-MEASUREMENT.md record tables, in their order and units."""
    generated = [probe for probe in probes if probe["media_seconds"] < CAP_SECONDS - 1]
    probe_rows = []
    if generated:
        probe_rows.append({"target": "generated clips", "demuxer": ", ".join(sorted({p["demuxer"] for p in generated})),
                           "media_seconds": [min(p["media_seconds"] for p in generated),
                                             max(p["media_seconds"] for p in generated)],
                           "worst_seconds": max(p["worst_seconds"] for p in generated)})
    probe_rows += [{"target": probe["clip"], "demuxer": probe["demuxer"], "media_seconds": [probe["media_seconds"]] * 2,
                    "worst_seconds": probe["worst_seconds"]} for probe in probes if probe not in generated]
    encode_rows, cap_rows = [], []
    for fit, item in zip(fits, predicted):
        subset = [run for run in runs if run["kind"] == fit["kind"] and run["threads"] == fit["threads"]
                  and not run["long"]]
        encode_rows.append({"kind": fit["kind"], "threads": fit["threads"], "long": False,
                            "encode_seconds_per_media_second": fit["encode_seconds_per_media_second"],
                            "fixed_overhead_seconds": fit["fixed_overhead_seconds"],
                            "steady_output_kbps": fit["output_bytes_per_media_second"] * 8 / 1000, **run_peaks(subset)})
        cap_rows.append({"kind": item["kind"], "threads": item["threads"], "predicted": True,
                         "encode_seconds": item["encode_seconds"], "output_bytes": item["output_bytes"],
                         "temp_disk_peak_bytes": UPLOAD_CAP_BYTES + item["output_bytes"]})
    # A long run is measured, not fitted: its own ratio includes the fixed overhead, which is small at length.
    encode_rows += [{"kind": run["kind"], "threads": run["threads"], "long": True, "media_seconds": run["media_seconds"],
                     "encode_seconds_per_media_second": run["encode_seconds_per_media_second"],
                     "fixed_overhead_seconds": None, "steady_output_kbps": run["output"]["kbps"], **run_peaks([run])}
                    for run in runs if run["long"]]
    return {"probes": probe_rows, "encode": encode_rows, "at_cap": cap_rows}


class Docker:
    """Thin wrapper so every container of a run carries one label and can be found for cleanup."""

    def __init__(self, image, token):
        self.image, self.token = image, token
        self.label = "vod.encode-measurement=" + token
        self.user = [] if not hasattr(os, "getuid") else ["--user", f"{os.getuid()}:{os.getgid()}"]

    @staticmethod
    def command(args, timeout=120, check=True):
        try:
            result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace",
                                    timeout=timeout)
        except subprocess.TimeoutExpired:
            raise MeasurementError("Timed out: " + " ".join(args[:3])) from None
        if check and result.returncode:
            raise MeasurementError("Failed: " + " ".join(args[:3]) + ": " + result.stderr.strip()[-500:])
        return result

    def run_args(self, name, mounts, limits=None):
        # The image's TCP health check would otherwise run inside the measured cgroup every 30 s.
        args = ["docker", "run", "--name", name, "--label", self.label, "--network", "none", "--no-healthcheck",
                *self.user]
        if limits:
            args += ["--cpus", limits["cpus"], "--memory", f"{limits['memory_mib']}m"]
        for source, target, read_only in mounts:
            args += ["--mount", f"type=bind,source={source},target={target}" + (",readonly" if read_only else "")]
        return args + [self.image]

    def cleanup(self):
        ids = self.command(["docker", "ps", "-aq", "--filter", "label=" + self.label], check=False).stdout.split()
        if ids:
            self.command(["docker", "rm", "-f", *ids], check=False)


class Sampler:
    """docker stats stream plus a temp-disk poll of the run's output directory."""

    def __init__(self, container, directory):
        self.container, self.directory = container, directory
        self.stats, self.disk = [], []
        self.stop = threading.Event()
        self.process = subprocess.Popen(["docker", "stats", "--format", "{{json .}}", container],
                                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                                        encoding="utf-8", errors="replace")
        self.readers = [threading.Thread(target=self.read_stats, daemon=True),
                        threading.Thread(target=self.poll_disk, daemon=True)]
        for reader in self.readers:
            reader.start()

    def read_stats(self):
        for line in self.process.stdout:
            try:
                sample = parse_stats_line(line)
            except (ValueError, KeyError, MeasurementError):
                continue
            if sample:
                self.stats.append(sample)

    def poll_disk(self):
        while not self.stop.is_set():
            self.disk.append(allocated_bytes(self.directory))
            self.stop.wait(0.5)

    def finish(self):
        self.stop.set()
        self.disk.append(allocated_bytes(self.directory))
        self.process.terminate()
        try:
            self.process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            self.process.kill()
        for reader in self.readers:
            reader.join(timeout=15)


class Measurement:
    def __init__(self, args, work, docker):
        self.args, self.work, self.docker = args, work, docker
        self.clips_dir = work / "clips"
        self.clips_dir.mkdir()
        self.counter = 0
        self.limits = worker_limits((ROOT / "deploy/docker-compose.prod.yml").read_text(encoding="utf-8"))

    def name(self, purpose):
        self.counter += 1
        return f"vod-encode-measure-{self.docker.token}-{self.counter}-{purpose}"

    def in_container(self, purpose, tool_args, run_dir, limits, timeout):
        """Runs ffmpeg/ffprobe through the wrapper; returns metrics, stdout and the sampler (if any)."""
        run_dir.mkdir(parents=True, exist_ok=True)
        name = self.name(purpose)
        mounts = [(str(self.clips_dir), "/measure/clips", purpose != "generate"), (str(run_dir), "/measure/run", False)]
        args = self.docker.run_args(name, mounts, limits) + ["sh", "-c", WRAPPER, "sh", "/measure/run/metrics", *tool_args]
        args.insert(2, "-d")
        started = time.monotonic()
        self.docker.command(args)
        sampler = Sampler(name, run_dir / "hls") if purpose == "encode" else None
        try:
            waited = self.docker.command(["docker", "wait", name], timeout=timeout, check=False)
        except MeasurementError:
            self.docker.command(["docker", "rm", "-f", name], check=False)
            raise MeasurementError(f"{purpose} exceeded {timeout} s") from None
        finally:
            if sampler:
                sampler.finish()
        wall = time.monotonic() - started
        self.docker.command(["docker", "rm", "-f", name], check=False)
        if waited.returncode or not waited.stdout.strip().isdigit():
            raise MeasurementError(f"Cannot read the {purpose} container exit status")
        metrics_file = run_dir / "metrics"
        if not metrics_file.is_file():
            raise MeasurementError(f"The {purpose} container wrote no metrics")
        try:
            metrics = parse_metrics(metrics_file.read_text(encoding="utf-8", errors="replace"))
        except MeasurementError as error:
            raise MeasurementError(f"The {purpose} container metrics: {error}") from None
        metrics["container_wall_seconds"] = wall
        stdout = (run_dir / "metrics.stdout").read_text(encoding="utf-8", errors="replace")
        stderr = (run_dir / "metrics.stderr").read_text(encoding="utf-8", errors="replace")[-2000:]
        return metrics, stdout, stderr, sampler

    def generate(self, file_name, tool_args):
        metrics, _, stderr, _ = self.in_container("generate", ["ffmpeg", *tool_args],
                                                  self.work / "generate", None, GENERATE_TIMEOUT_SECONDS)
        if metrics["exit_code"]:
            raise MeasurementError(f"Generating {file_name} failed: {stderr.strip()}")
        return self.clips_dir / file_name

    def clip(self, kind, seconds, long=False):
        file_name = f"{kind}-{'long-' if long else ''}{seconds}s.mp4"
        kbps = long_clip_video_kbps(seconds) if long else CLIPS[kind]["video_kbps"]
        path = self.generate(file_name, source_arguments(kind, seconds, "/measure/clips/" + file_name, kbps))
        return {"name": file_name, "kind": kind, "seconds": seconds, "long": long, "path": path,
                "bytes": path.stat().st_size, "demuxer": "mov",
                "source": {"size": f"{CAP_SIZE[0]}x{CAP_SIZE[1]}", "fps": FRAME_RATE, "video": "H.264 High 8-bit 4:2:0",
                           "video_kbps": kbps, "audio": f"AAC-LC {AUDIO_KBPS} kb/s stereo 48 kHz",
                           "content": CLIPS[kind]["description"], "container": "MP4 (faststart)"}}

    def cap_probe_clips(self):
        """Cap-length files for the probe only: a 10 s black segment repeated by stream copy to 10800 s."""
        segment = "segment-10s.mp4"
        self.generate(segment, ["-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                                "-f", "lavfi", "-i", f"color=c=black:s={CAP_SIZE[0]}x{CAP_SIZE[1]}:r={FRAME_RATE}:d=10",
                                "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-t", "10",
                                "-c:v", "libx264", "-preset", "superfast", "-profile:v", "high", "-pix_fmt", "yuv420p",
                                "-c:a", "aac", "-b:a", f"{AUDIO_KBPS}k", "-movflags", "+faststart",
                                "/measure/clips/" + segment])
        (self.clips_dir / "segments.txt").write_text(
            "ffconcat version 1.0\n" + f"file '{segment}'\n" * (CAP_SECONDS // 10), encoding="utf-8")
        clips = []
        for file_name, extra in (("cap-probe.mp4", ["-movflags", "+faststart"]), ("cap-probe.mkv", [])):
            path = self.generate(file_name, ["-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                                             "-f", "concat", "-safe", "0", "-i", "/measure/clips/segments.txt",
                                             "-c", "copy", *extra, "/measure/clips/" + file_name])
            clips.append({"name": file_name, "kind": "cap-probe", "seconds": CAP_SECONDS, "long": False, "path": path,
                          "bytes": path.stat().st_size, "demuxer": "mov" if file_name.endswith(".mp4") else "matroska"})
        (self.clips_dir / segment).unlink()
        return clips

    def probe(self, clip):
        times, duration = [], None
        for repeat in range(PROBE_REPEATS):
            metrics, stdout, stderr, _ = self.in_container(
                "probe", ["ffprobe", *probe_arguments("/measure/clips/" + clip["name"], clip["demuxer"])],
                self.work / "probe", self.limits, PROBE_TIMEOUT_SECONDS)
            if metrics["exit_code"]:
                raise MeasurementError(f"Probing {clip['name']} failed: {stderr.strip()}")
            times.append(metrics["seconds"])
            duration = media_duration(json.loads(stdout))
        return {"clip": clip["name"], "demuxer": clip["demuxer"], "bytes": clip["bytes"], "media_seconds": duration,
                "seconds": times, "worst_seconds": max(times)}

    def encode(self, clip, media_seconds, threads):
        run_dir = self.work / f"encode-{clip['name']}-threads-{threads}"
        (run_dir / "hls" / "segments").mkdir(parents=True)
        tool_args = ["ffmpeg", *reference_arguments("/measure/clips/" + clip["name"], clip["demuxer"], "/measure/run/hls",
                                                    BOX[0], BOX[1], threads)]
        timeout = int(max(1800, 60 * media_seconds))
        metrics, stdout, stderr, sampler = self.in_container("encode", tool_args, run_dir, self.limits, timeout)
        violations, output_bytes, segments, extinf = output_check(run_dir / "hls", media_seconds)
        if metrics["exit_code"]:
            violations.insert(0, f"ffmpeg exited {metrics['exit_code']}: {stderr.strip()}")
        progress = dict(line.split("=", 1) for line in stdout.splitlines()[-40:] if "=" in line)
        hls_peak = max(sampler.disk, default=0)
        run = {"clip": clip["name"], "kind": clip["kind"], "long": clip["long"], "threads": threads,
               "media_seconds": media_seconds, "encode_seconds": metrics["seconds"],
               "encode_seconds_per_media_second": metrics["seconds"] / media_seconds,
               "container_wall_seconds": metrics["container_wall_seconds"], "exit_code": metrics["exit_code"],
               "valid": not violations, "violations": violations,
               "progress_end": {key: progress.get(key) for key in ("out_time_us", "speed", "progress")},
               "output": {"bytes": output_bytes, "segments": segments, "extinf_seconds": extinf,
                          "bytes_per_second": output_bytes / media_seconds,
                          "kbps": output_bytes * 8 / media_seconds / 1000,
                          "d27_budget_bytes": d27_budget_bytes(media_seconds),
                          "within_d27_budget": output_bytes <= d27_budget_bytes(media_seconds)},
               "temp_disk": {"source_bytes": clip["bytes"], "hls_peak_bytes": hls_peak,
                             "peak_bytes": clip["bytes"] + hls_peak, "samples": len(sampler.disk),
                             "note": "Raw copy plus the HLS directory, polled every 0.5 s"},
               "docker_stats": summarize_stats(sampler.stats), "cgroup": metrics["cgroup"]}
        shutil.rmtree(run_dir, ignore_errors=True)
        return run


def environment_report(docker, image):
    info = json.loads(docker.command(["docker", "info", "--format", "{{json .}}"]).stdout)
    inspected = json.loads(docker.command(["docker", "image", "inspect", image]).stdout)[0]
    # Checked before any container starts: an image of another architecture would run under emulation.
    daemon_architecture = {"x86_64": "amd64", "aarch64": "arm64"}.get(info.get("Architecture"), info.get("Architecture"))
    if inspected.get("Architecture") != daemon_architecture:
        raise MeasurementError("The image architecture differs from the Docker host: emulated runs are not measured")
    versions = docker.command(docker.run_args("vod-encode-measure-" + docker.token + "-versions", [])
                              + ["sh", "-c", "ffmpeg -version | head -n 1; "
                                             "dpkg-query -W ffmpeg openjdk-21-jre-headless libx264-164"]).stdout
    docker.command(["docker", "rm", "-f", "vod-encode-measure-" + docker.token + "-versions"], check=False)
    lines = versions.splitlines()
    cpu_model = None
    if shutil.which("lscpu"):
        model = re.search(r"^Model name:\s*(.+)$", Docker.command(["lscpu"], check=False).stdout, re.M)
        cpu_model = model.group(1).strip() if model else None
    return {
        "host": {"machine": platform.machine(), "cpu_model": cpu_model,
                 "docker": {key: info.get(key) for key in ("Architecture", "NCPU", "MemTotal", "KernelVersion",
                                                           "OperatingSystem", "CgroupVersion", "ServerVersion")},
                 "github": {key: os.environ.get(key) for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "RUNNER_OS",
                                                                 "RUNNER_ARCH", "RUNNER_ENVIRONMENT", "GITHUB_REF")}},
        "image": {"reference": image, "id": inspected["Id"], "os": inspected.get("Os"),
                  "architecture": inspected.get("Architecture"), "ffmpeg": lines[0] if lines else None,
                  "packages": dict(line.split("\t", 1) for line in lines[1:] if "\t" in line)},
    }


def write_report(output, report):
    output.mkdir(parents=True, exist_ok=True)
    (output / "measurement.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    summary = render_summary(report)
    (output / "summary.md").write_text(summary, encoding="utf-8")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as destination:
            destination.write(summary)


def render_summary(report):
    def number(value, digits=2):
        return "n/a" if value is None else f"{value:.{digits}f}"

    def mib(value):
        return "n/a" if value is None else f"{value / 1024**2:.1f} MiB"

    lines = [f"# {LABEL}", "", f"Evidence: {report.get('evidence') or evidence()}.", "",
             f"Result: **{report['result']}**", ""]
    environment = report.get("environment", {})
    if environment:
        host, image = environment["host"], environment["image"]
        lines += [f"Host: {host['docker'].get('Architecture')}, {host['docker'].get('NCPU')} CPUs, "
                  f"CPU model {host.get('cpu_model') or 'not reported'}, kernel {host['docker'].get('KernelVersion')}.",
                  f"Image: `{image['reference']}` ({image['architecture']}), {image['ffmpeg']}.",
                  f"Limits per container: `--cpus {report['limits']['cpus']} --memory {report['limits']['memory_mib']}m "
                  "--network none` (production worker limits).", ""]
    lines += ["Measured runs (each in its own container):", "",
              "| Clip | Threads | Media s | Encode s | Encode s per media s | Output kb/s | Output within D27 B | "
              "Temp-disk peak | CPU % peak | MemUsage peak | memory.peak | PIDs peak | Valid |", "|" + " --- |" * 13]
    for run in report["runs"]:
        stats = run["docker_stats"]
        lines.append(f"| {run['clip']} | {run['threads']} | {number(run['media_seconds'], 1)} | "
                     f"{number(run['encode_seconds'])} | {number(run['encode_seconds_per_media_second'], 3)} | "
                     f"{number(run['output']['kbps'], 0)} | {'yes' if run['output']['within_d27_budget'] else 'NO'} | "
                     f"{mib(run['temp_disk']['peak_bytes'])} | "
                     f"{number(stats.get('cpu_percent_peak'), 0)} | {mib(stats.get('memory_usage_peak_bytes'))} | "
                     f"{mib(run['cgroup'].get('memory.peak'))} | {stats.get('pids_peak')} | "
                     f"{'yes' if run['valid'] else 'NO: ' + '; '.join(run['violations'])} |")
    lines += ["", "| Probe target | Demuxer | Media s | Worst probe s |", "| --- | --- | --- | --- |"]
    for probe in report["probes"]:
        lines.append(f"| {probe['clip']} | {probe['demuxer']} | {number(probe['media_seconds'], 1)} | "
                     f"{number(probe['worst_seconds'], 3)} |")
    derived = report.get("derived")
    if derived:
        lines += ["", "| Least-squares fit over the measured clips | Encode s per media s | Fixed overhead s | "
                      "Output kb/s (steady) | Output fixed bytes |", "| --- | --- | --- | --- | --- |"]
        for fit in derived["fits"]:
            lines.append(f"| {fit['kind']}, threads {fit['threads']}, {fit['media_seconds']} s | "
                         f"{number(fit['encode_seconds_per_media_second'], 3)} | {number(fit['fixed_overhead_seconds'])} | "
                         f"{number(fit['output_bytes_per_media_second'] * 8 / 1000, 0)} | "
                         f"{number(fit['output_fixed_bytes'], 0)} |")
        prediction = derived["predicted_at_cap"]
        lines += ["", f"**{prediction['label']}**", ""]
        for item in prediction["encode_seconds"]:
            lines.append(f"- {item['kind']}, threads {item['threads']}: about {number(item['encode_seconds'] / 3600)} h "
                         f"encode and {mib(item['output_bytes'])} of HLS (predicted)")
        lines += [f"- Largest HLS output: about {mib(prediction['output_bytes'])} (predicted); temp-disk peak with a "
                  f"2048 MiB raw copy: about {mib(prediction['temp_disk_peak_bytes'])} (predicted)",
                  f"- ADR-010 D27 budget B at the cap: {mib(prediction['d27_budget_bytes'])}; free space required after "
                  f"probing: {mib(prediction['d27_free_bytes_required_after_probe'])}"]
        lines += render_record(derived.get("record"), number, mib)
    if report.get("failure"):
        lines += ["", "Failure: " + report["failure"]]
    lines += ["", "Timings are not an NFR-002 baseline; the owner sets the timeouts with a margin "
                  "(docs/testing/WORKER-ENCODE-MEASUREMENT.md).", ""]
    return "\n".join(lines)


def render_record(record, number, mib):
    """The record tables of docs/testing/WORKER-ENCODE-MEASUREMENT.md, filled from this run."""
    if not record:
        return []
    lines = ["", "## Record rows", "",
             "Rows for the tables of docs/testing/WORKER-ENCODE-MEASUREMENT.md, in the same columns and units.", "",
             "FFprobe:", "", "| Target | Demuxer | Media s | Worst probe s (of 3) |", "| --- | --- | --- | --- |"]
    for row in record["probes"]:
        low, high = (number(value, 0) for value in row["media_seconds"])
        lines.append(f"| {row['target']} | {row['demuxer']} | {low if low == high else low + '-' + high} | "
                     f"{number(row['worst_seconds'], 3)} |")
    lines += ["", "FFmpeg reference encode (measured):", "",
              "| Clip type | Threads | Encode s per media s | Fixed overhead s | Steady output kb/s | Largest temp-disk "
              "peak | CPU % peak | MemUsage peak | `memory.peak` | PIDs peak |", "|" + " --- |" * 10]
    for row in record["encode"]:
        clip_type = (f"Long {row['kind']} ({number(row['media_seconds'], 0)} s)" if row["long"]
                     else row["kind"].capitalize())
        lines.append(f"| {clip_type} | {row['threads']} | {number(row['encode_seconds_per_media_second'], 3)} | "
                     f"{number(row['fixed_overhead_seconds'])} | {number(row['steady_output_kbps'], 0)} | "
                     f"{mib(row['temp_disk_peak_bytes'])} | {number(row['cpu_percent_peak'], 0)} | "
                     f"{mib(row['memory_usage_peak_bytes'])} | {mib(row['cgroup_memory_peak_bytes'])} | "
                     f"{'n/a' if row['pids_peak'] is None else row['pids_peak']} |")
    lines += ["", f"At the {CAP_SECONDS} s cap (predicted, not measured):", "",
              "| Clip type, threads | Encode duration | HLS output | Temp-disk peak with a 2048 MiB raw copy |",
              "| --- | --- | --- | --- |"]
    for row in record["at_cap"]:
        lines.append(f"| {row['kind'].capitalize()}, {row['threads']} | {number(row['encode_seconds'] / 3600)} h "
                     f"(predicted) | {mib(row['output_bytes'])} (predicted) | {mib(row['temp_disk_peak_bytes'])} "
                     "(predicted) |")
    return lines


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--image", required=True, help="worker image built from worker/Dockerfile (final stage)")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--work-dir", type=Path, help="parent of the fresh work directory (default: system temp)")
    parser.add_argument("--clip-seconds", type=clip_seconds, default=clip_seconds("2 20 60"))
    parser.add_argument("--threads", type=thread_counts, default=thread_counts("1 2"))
    parser.add_argument("--long-clip-seconds", type=long_seconds, default=0,
                        help="opt-in high-entropy clip of this length (10800 for the cap); 0 for none")
    parser.add_argument("--long-clip-threads", type=long_threads, default=1,
                        help="the one WORKER_FFMPEG_THREADS value the long clip is encoded with (default 1)")
    parser.add_argument("--skip-cap-probe", action="store_true", help="skip the probe of cap-length files")
    parser.add_argument("--keep-work", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/:@-]*", args.image):
        raise MeasurementError("Unexpected image reference")
    token = secrets.token_hex(6)
    docker = Docker(args.image, token)
    parent = args.work_dir or Path(os.environ.get("RUNNER_TEMP") or tempfile.gettempdir())
    if not parent.is_dir():
        raise MeasurementError("The work directory parent does not exist")
    work = Path(tempfile.mkdtemp(prefix="vod-encode-measure-", dir=parent)).resolve()
    report = {"label": LABEL, "evidence": evidence(), "result": "FAIL",
              "started_at": datetime.now(timezone.utc).isoformat(),
              "commit": os.environ.get("GITHUB_SHA"), "clip_seconds": args.clip_seconds, "threads": args.threads,
              "long_clip_seconds": args.long_clip_seconds, "long_clip_threads": args.long_clip_threads,
              "probes": [], "runs": [], "clips": [],
              "reference_arguments": reference_arguments("/measure/clips/<clip>", "<demuxer>", "/measure/run/hls",
                                                         BOX[0], BOX[1], "<WORKER_FFMPEG_THREADS>"),
              "probe_arguments": probe_arguments("/measure/clips/<clip>", "<demuxer>"),
              "settings": {"HLS_SEGMENT_SECONDS": SEGMENT_SECONDS, "HLS_TARGET_HEIGHT": BOX[1],
                           "HLS_VIDEO_MAXRATE_KBPS": 4000, "WORKER_MAX_STREAMS": MAX_STREAMS,
                           "WORKER_MAX_DURATION_SECONDS": CAP_SECONDS, "WORKER_OUTPUT_BUDGET_KBPS": OUTPUT_BUDGET_KBPS,
                           "WORKER_DISK_RESERVE_MB": DISK_RESERVE_MB, "MAX_UPLOAD_SIZE_MB": UPLOAD_CAP_BYTES // 1024**2},
              "notes": ["The probe has no -codec_whitelist yet: Issue 5.4 fixes the D26 decoder list.",
                        "Each probe and encode runs in a fresh container, so its cgroup peaks are its own; in "
                        "production FFmpeg shares the worker container with the worker JVM.",
                        "Clip sources are generated at the D26 size caps; short high-entropy clips use a 40 Mb/s "
                        "source, a long clip the average rate a 2048 MiB upload of that length can have."]}
    failure = None
    try:
        measurement = Measurement(args, work, docker)
        report["limits"] = measurement.limits
        report["environment"] = environment_report(docker, args.image)
        kinds = list(CLIPS)
        # All sources, the cap-length probe files (about 80 MB), one HLS output at a time with 10% over the
        # D27 budget, and a reserve.
        needed = WORK_RESERVE_BYTES + 128 * 1024**2
        for seconds in args.clip_seconds:
            needed += sum((CLIPS[kind]["video_kbps"] + AUDIO_KBPS) * 125 * seconds for kind in kinds)
        needed += int(1.1 * d27_budget_bytes(max(args.clip_seconds + [args.long_clip_seconds])))
        if args.long_clip_seconds:
            needed += UPLOAD_CAP_BYTES
        report["disk"] = {"work_free_bytes": shutil.disk_usage(work).free, "needed_bytes": needed}
        if report["disk"]["work_free_bytes"] < needed:
            raise MeasurementError("Not enough free disk for the clips, one output and a 1 GiB reserve")
        clips = [measurement.clip(kind, seconds) for kind in kinds for seconds in args.clip_seconds]
        if args.long_clip_seconds:
            clips.append(measurement.clip("high-entropy", args.long_clip_seconds, long=True))
        probe_targets = list(clips) if args.skip_cap_probe else clips + measurement.cap_probe_clips()
        durations = {}
        for clip in probe_targets:
            probe = measurement.probe(clip)
            report["probes"].append(probe)
            durations[clip["name"]] = probe["media_seconds"]
        report["clips"] = [{key: value for key, value in clip.items() if key != "path"} for clip in probe_targets]
        for clip in probe_targets:
            if clip["kind"] == "cap-probe":
                clip["path"].unlink()
        for clip in clips:
            for threads in [args.long_clip_threads] if clip["long"] else args.threads:
                print(f"Encoding {clip['name']} with WORKER_FFMPEG_THREADS={threads}", flush=True)
                run = measurement.encode(clip, durations[clip["name"]], threads)
                report["runs"].append(run)
                if not run["valid"]:
                    raise MeasurementError(f"{clip['name']} with threads {threads} produced no valid output")
        report["derived"] = derive(report)
        report["result"] = "PASS"
    except MeasurementError as error:
        failure = str(error)
    except Exception as error:  # Report the step without a traceback; the run must still clean up.
        failure = f"Unexpected {type(error).__name__}: {error}"
    finally:
        docker.cleanup()
        if not args.keep_work:
            shutil.rmtree(work, ignore_errors=True)
        if failure:
            report["failure"] = failure
            if report["runs"] and "derived" not in report:
                report["derived"] = derive(report)
        report["completed_at"] = datetime.now(timezone.utc).isoformat()
        write_report(args.output_dir, report)
    if failure:
        raise MeasurementError(failure)
    print("PASS " + LABEL, flush=True)


if __name__ == "__main__":
    def interrupted(_signum, _frame):
        raise MeasurementError("Measurement interrupted")
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        main()
    except MeasurementError as error:
        print("FAIL " + str(error), file=sys.stderr)
        sys.exit(1)
