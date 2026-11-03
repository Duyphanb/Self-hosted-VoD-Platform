"""Measurement helpers of worker-encode-measurement.py (#124), without Docker or FFmpeg."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("worker_encode_measurement",
                                              Path(__file__).with_name("worker-encode-measurement.py"))
MEASURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MEASURE)
REFERENCE = (MEASURE.ROOT / "worker/test-support/src/test/java/com/vodplatform/worker/testsupport"
             / "ReferenceHlsEncodeFfmpegTests.java")
STRING = r'"(?:[^"\\]|\\.)*"'


def java_reference_arguments(input_path, demuxer, hls, box_width, box_height):
    """Evaluates the List.of(...) of ReferenceHlsEncodeFfmpegTests.referenceArguments for fixed arguments.

    Only the expressions below are understood; any other expression fails, so a change to the Java
    reference must be mirrored in the measurement script and here.
    """
    source = REFERENCE.read_text(encoding="utf-8")
    body = re.search(r"static List<String> referenceArguments\([^)]*\) \{\s*return List\.of\((.*?)\);\s*\}",
                     source, re.S).group(1)
    expressions = {
        "demuxer.ffmpegName()": demuxer, "input.toAbsolutePath()": input_path,
        "boxWidth": str(box_width), "boxHeight": str(box_height),
        "SEGMENT_SECONDS": "6", "String.valueOf(SEGMENT_SECONDS)": "6",
        'hls.resolve("segments/seg_%05d.ts").toString()': hls + "/segments/seg_%05d.ts",
        "hls.resolve(HlsOutputCheck.PLAYLIST).toString()": hls + "/master.m3u8",
    }

    def split(text, separator):
        parts, depth, current = [], 0, ""
        for token in re.findall(STRING + r"|.", text, re.S):
            if token.startswith('"') and len(token) > 1:
                current += token
                continue
            depth += token == "("
            depth -= token == ")"
            if token == separator and depth == 0:
                parts.append(current)
                current = ""
            else:
                current += token
        return parts + [current]

    arguments = []
    for argument in split(body, ","):
        value = ""
        for part in (part.strip() for part in split(argument, "+")):
            if re.fullmatch(STRING, part):
                value += re.sub(r"\\(.)", r"\1", part[1:-1])
            else:
                value += expressions[re.sub(r"\s+", "", part)]
        arguments.append(value)
    return arguments


class ReferenceCommandTests(unittest.TestCase):
    def test_measured_command_is_the_java_reference_argument_list(self):
        expected = java_reference_arguments("/measure/clips/a.mp4", "mov", "/measure/run/hls", 1280, 720)
        self.assertEqual(MEASURE.reference_arguments("/measure/clips/a.mp4", "mov", "/measure/run/hls", 1280, 720, 1),
                         expected)
        self.assertIn("4000k", expected)
        self.assertIn("scale=w=min(1280\\,iw):h=min(720\\,ih)", expected[expected.index("-vf") + 1])

    def test_thread_setting_changes_only_the_two_output_thread_options(self):
        one = MEASURE.reference_arguments("/in.mp4", "matroska", "/hls", 1280, 720, 1)
        two = MEASURE.reference_arguments("/in.mp4", "matroska", "/hls", 1280, 720, 2)
        changed = [index for index, (left, right) in enumerate(zip(one, two)) if left != right]
        self.assertEqual([one[index - 1] for index in changed], ["-threads", "-filter_threads"])
        self.assertEqual([two[index] for index in changed], ["2", "2"])
        # Both are output options: they follow the input and precede the HLS muxer options.
        self.assertGreater(changed[0], one.index("-i"))
        self.assertLess(changed[-1], one.index("hls"))

    def test_probe_uses_the_trusted_demuxer_file_protocol_and_stream_cap(self):
        arguments = MEASURE.probe_arguments("/measure/clips/a.mkv", "matroska")
        self.assertEqual(arguments[arguments.index("-f") + 1], "matroska")
        self.assertEqual(arguments[arguments.index("-protocol_whitelist") + 1], "file")
        self.assertEqual(arguments[arguments.index("-max_streams") + 1], "8")
        self.assertEqual(arguments[-1], "file:/measure/clips/a.mkv")


class ClipTests(unittest.TestCase):
    def test_sources_are_at_the_size_caps_and_named_rates(self):
        for kind, clip in MEASURE.CLIPS.items():
            arguments = MEASURE.source_arguments(kind, 20, "/measure/clips/x.mp4")
            with self.subTest(kind=kind):
                self.assertIn("s=1920x1080", arguments[arguments.index("-i") + 1])
                self.assertIn("d=20", arguments[arguments.index("-i") + 1])
                self.assertEqual(arguments[arguments.index("-b:v") + 1], f"{clip['video_kbps']}k")
                self.assertEqual(arguments[arguments.index("-t") + 1], "20")
                self.assertEqual(arguments[-1], "/measure/clips/x.mp4")

    def test_long_clip_rate_keeps_a_cap_length_source_within_the_upload_cap(self):
        for seconds in (3600, 7200, 10800):
            with self.subTest(seconds=seconds):
                kbps = MEASURE.long_clip_video_kbps(seconds)
                total_bytes = (kbps + MEASURE.AUDIO_KBPS) * 1000 / 8 * seconds
                self.assertLessEqual(total_bytes, MEASURE.UPLOAD_CAP_BYTES)
        self.assertEqual(MEASURE.long_clip_video_kbps(60), MEASURE.CLIPS["high-entropy"]["video_kbps"])

    def test_duration_and_thread_inputs_are_validated(self):
        self.assertEqual(MEASURE.clip_seconds("60, 2 20"), [2, 20, 60])
        self.assertEqual(MEASURE.thread_counts("2 1"), [1, 2])
        self.assertEqual(MEASURE.long_seconds("0"), 0)
        self.assertEqual(MEASURE.long_seconds("10800"), 10800)
        for parser, value in ((MEASURE.clip_seconds, "20"), (MEASURE.clip_seconds, "20 20"),
                              (MEASURE.clip_seconds, "2 10801"), (MEASURE.clip_seconds, "2 x"),
                              (MEASURE.clip_seconds, "2 -1"), (MEASURE.clip_seconds, "2 $(id)"),
                              (MEASURE.thread_counts, "0"), (MEASURE.thread_counts, "17"),
                              (MEASURE.long_seconds, "10801"), (MEASURE.long_seconds, "60 120")):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                parser(value)

    def test_containers_run_isolated_and_at_the_production_limits(self):
        docker = MEASURE.Docker("vod-worker-arm64", "0123456789ab")
        args = docker.run_args("vod-encode-measure-0123456789ab-1-encode",
                               [("/work/clips", "/measure/clips", True), ("/work/run", "/measure/run", False)],
                               {"cpus": "1.00", "memory_mib": 1024})
        self.assertEqual(args[:2], ["docker", "run"])
        self.assertEqual(args[-1], "vod-worker-arm64")
        for option, value in (("--network", "none"), ("--cpus", "1.00"), ("--memory", "1024m"),
                              ("--label", "vod.encode-measurement=0123456789ab")):
            self.assertEqual(args[args.index(option) + 1], value)
        self.assertIn("--no-healthcheck", args)
        self.assertIn("type=bind,source=/work/clips,target=/measure/clips,readonly", args)
        self.assertIn("type=bind,source=/work/run,target=/measure/run", args)
        self.assertNotIn("--cpus", docker.run_args("generate", [], None))

    def test_limits_come_from_the_production_worker_service(self):
        overlay = (MEASURE.ROOT / "deploy/docker-compose.prod.yml").read_text(encoding="utf-8")
        worker = re.search(r"^  worker:\n(.*?)^  frontend:", overlay, re.S | re.M).group(1)
        self.assertIn('cpus: "1.00"', worker)
        self.assertEqual(MEASURE.worker_limits(overlay), {"cpus": "1.00", "memory_mib": 1024})
        with self.assertRaises(MEASURE.MeasurementError):
            MEASURE.worker_limits(overlay.replace("  worker:\n", "  renamed:\n"))


class ParsingTests(unittest.TestCase):
    def test_wrapper_metrics_give_seconds_exit_status_and_cgroup_counters(self):
        metrics = MEASURE.parse_metrics("start_ns=1000000000\nend_ns=3500000000\nexit_code=0\nmemory.peak=268435456\n"
                                        "memory.max=1073741824\ncpu.max=100000 100000\nmemory.stat.anon=1024\n"
                                        "cpu.stat.nr_throttled=12\nmemory.events.oom_kill=0\n")
        self.assertEqual(metrics["seconds"], 2.5)
        self.assertEqual(metrics["exit_code"], 0)
        self.assertEqual(metrics["cgroup"]["memory.peak"], 268435456)
        self.assertEqual(metrics["cgroup"]["cpu.max"], "100000 100000")
        self.assertEqual(metrics["cgroup"]["cpu.stat.nr_throttled"], 12)
        for broken in ("", "start_ns=1\nend_ns=2\n", "start_ns=5\nend_ns=1\nexit_code=0\n", "garbage\n",
                       "start_ns=1\nend_ns=x\nexit_code=0\n"):
            with self.subTest(broken=broken), self.assertRaises(MEASURE.MeasurementError):
                MEASURE.parse_metrics(broken)

    def test_docker_stats_rows_strip_terminal_codes_and_skip_missing_samples(self):
        row = {"CPUPerc": "99.87%", "MemUsage": "312.5MiB / 1GiB", "PIDs": "23"}
        sample = MEASURE.parse_stats_line("\x1b[2J\x1b[H" + json.dumps(row) + "\n")
        self.assertEqual(sample, {"cpu_percent": 99.87, "memory_usage_bytes": int(312.5 * 1024**2),
                                  "memory_limit_bytes": 1024**3, "pids": 23})
        self.assertIsNone(MEASURE.parse_stats_line(json.dumps({"CPUPerc": "--", "MemUsage": "-- / --", "PIDs": "--"})))
        self.assertIsNone(MEASURE.parse_stats_line(json.dumps({"CPUPerc": "0.00%", "MemUsage": "0B / 0B", "PIDs": "0"})))
        self.assertIsNone(MEASURE.parse_stats_line("\x1b[2J\x1b[H"))
        with self.assertRaises(MEASURE.MeasurementError):
            MEASURE.size_bytes("12 parsecs")

    def test_fit_gives_rate_and_fixed_overhead(self):
        fit = MEASURE.linear_fit([(2, 5.0), (20, 41.0), (60, 121.0)])
        self.assertAlmostEqual(fit["slope"], 2.0)
        self.assertAlmostEqual(fit["intercept"], 1.0)
        with self.assertRaises(MEASURE.MeasurementError):
            MEASURE.linear_fit([(20, 1.0), (20, 2.0)])

    def test_media_duration_follows_adr_010_d26(self):
        probe = {"format": {"duration": "13.05"}, "streams": [
            {"codec_type": "video", "disposition": {"attached_pic": 1}, "duration": "0.04"},
            {"codec_type": "video", "disposition": {"attached_pic": 0}}]}
        self.assertEqual(MEASURE.media_duration(probe), 13.05)
        probe["streams"][1]["duration"] = "12.5"
        self.assertEqual(MEASURE.media_duration(probe), 12.5)
        with self.assertRaises(MEASURE.MeasurementError):
            MEASURE.media_duration({"streams": [{"codec_type": "audio"}]})


class OutputCheckTests(unittest.TestCase):
    def write(self, directory, playlist, segments=("seg_00000.ts", "seg_00001.ts"), extra=()):
        hls = Path(directory)
        (hls / "segments").mkdir()
        for name in segments:
            (hls / "segments" / name).write_bytes(b"\x47" * 188)
        for name in extra:
            (hls / name).write_bytes(b"x")
        (hls / "master.m3u8").write_bytes(playlist.encode("utf-8"))
        return hls

    VALID = ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-PLAYLIST-TYPE:VOD\n"
             "#EXTINF:6.000000,\nsegments/seg_00000.ts\n#EXTINF:4.000000,\nsegments/seg_00001.ts\n#EXT-X-ENDLIST\n")

    def test_complete_output_passes_and_reports_its_size(self):
        with tempfile.TemporaryDirectory() as directory:
            hls = self.write(directory, self.VALID)
            violations, total_bytes, segments, extinf = MEASURE.output_check(hls, 10.4)
            self.assertEqual(violations, [])
            self.assertEqual(segments, 2)
            self.assertEqual(extinf, 10.0)
            self.assertEqual(total_bytes, 2 * 188 + len(self.VALID.encode()))

    def test_truncated_short_or_polluted_output_is_not_a_measurement(self):
        cases = {"missing #EXT-X-ENDLIST": (self.VALID.replace("#EXT-X-ENDLIST\n", ""), 10.0, ()),
                 "EXTINF sum": (self.VALID, 13.0, ()),
                 "playlist type is not VOD": (self.VALID.replace("VOD", "EVENT"), 10.0, ()),
                 "unreferenced file": (self.VALID, 10.0, ("stray.ts",))}
        for expected, (playlist, seconds, extra) in cases.items():
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as directory:
                violations, _, _, _ = MEASURE.output_check(self.write(directory, playlist, extra=extra), seconds)
                self.assertTrue(any(violation.startswith(expected) for violation in violations), violations)


class DerivedValuesTests(unittest.TestCase):
    @staticmethod
    def run_record(kind, threads, seconds, encode, output_bytes, long=False):
        return {"clip": f"{kind}-{seconds:.0f}s.mp4", "kind": kind, "threads": threads, "media_seconds": seconds,
                "encode_seconds": encode, "encode_seconds_per_media_second": encode / seconds, "long": long,
                "valid": True, "violations": [],
                "output": {"bytes": output_bytes, "kbps": output_bytes * 8 / seconds / 1000,
                           "bytes_per_second": output_bytes / seconds,
                           "within_d27_budget": output_bytes <= MEASURE.d27_budget_bytes(seconds)},
                "temp_disk": {"peak_bytes": output_bytes + 1},
                "docker_stats": {"memory_usage_peak_bytes": 5, "pids_peak": 9, "cpu_percent_peak": 100.0},
                "cgroup": {"memory.peak": 7}}

    def test_extrapolation_is_labelled_predicted_and_uses_the_fits(self):
        # 1 MB of VBV start-up burst, then 500,000 bytes per media second.
        report = {"probes": [{"clip": "high-entropy-20s.mp4", "demuxer": "mov", "worst_seconds": 0.2,
                              "media_seconds": 20.0},
                             {"clip": "cap-probe.mkv", "demuxer": "matroska", "worst_seconds": 0.9,
                              "media_seconds": 10800.0}],
                  "runs": [self.run_record("high-entropy", 1, 2.0, 7.0, 2_000_000),
                           self.run_record("high-entropy", 1, 20.0, 61.0, 11_000_000),
                           self.run_record("high-entropy", 1, 600.0, 1900.0, 300_000_000, long=True),
                           self.run_record("typical", 1, 2.0, 3.0, 500_000)]}
        derived = MEASURE.derive(report)
        self.assertEqual(derived["measured"]["worst_probe_seconds"], 0.9)
        self.assertEqual(derived["measured"]["worst_probe_at_cap_seconds"], 0.9)
        self.assertEqual(derived["measured"]["max_encode_seconds_per_media_second"], 3.5)
        # The long run is measured evidence, but it is not one of the fitted short clips; one typical
        # duration gives no fit.
        self.assertEqual(len(derived["fits"]), 1)
        fit = derived["fits"][0]
        self.assertAlmostEqual(fit["encode_seconds_per_media_second"], 3.0)
        self.assertAlmostEqual(fit["fixed_overhead_seconds"], 1.0)
        self.assertAlmostEqual(fit["output_bytes_per_media_second"], 500_000)
        self.assertAlmostEqual(fit["output_fixed_bytes"], 1_000_000)
        prediction = derived["predicted_at_cap"]
        self.assertTrue(prediction["label"].startswith("PREDICTED"))
        self.assertAlmostEqual(prediction["encode_seconds"][0]["encode_seconds"], 1.0 + 3.0 * 10800)
        # The steady rate, not the 2 s clip's 1,000,000 bytes per second, scales to the cap.
        self.assertAlmostEqual(prediction["output_bytes"], 1_000_000 + 500_000 * 10800)
        self.assertEqual(prediction["temp_disk_peak_bytes"], MEASURE.UPLOAD_CAP_BYTES + prediction["output_bytes"])
        self.assertEqual(prediction["d27_budget_bytes"], 10800 * 625_000 + 16 * 1024**2)
        summary = MEASURE.render_summary({"result": "PASS", "runs": report["runs"], "probes": report["probes"],
                                          "derived": derived})
        self.assertIn("PREDICTED", summary)
        self.assertIn("not A1 VPS evidence", summary)
        self.assertIn("| high-entropy-600s.mp4 | 1 | 600.0 | 1900.00 | 3.167 |", summary)

    def test_d27_budget_covers_the_short_clip_burst_through_its_constant(self):
        self.assertEqual(MEASURE.d27_budget_bytes(2.0), 1_250_000 + 16 * 1024**2)
        self.assertEqual(MEASURE.d27_budget_bytes(0.5), 312_500 + 16 * 1024**2)


class WorkflowTests(unittest.TestCase):
    def test_arm64_job_is_separate_and_passes_dispatch_inputs_through_the_environment(self):
        workflow = (MEASURE.ROOT / ".github/workflows/arm64-worker.yml").read_text(encoding="utf-8")
        self.assertIn("runs-on: ubuntu-24.04-arm", workflow)
        self.assertIn("worker-encode-measurement.py", workflow)
        self.assertIn("deploy/tests/worker-ffmpeg-tests.sh", workflow)
        self.assertIn("jvm-limits-smoke.py", workflow)
        # Inputs reach scripts as environment variables, never as text substituted into a shell script.
        blocks = re.findall(r"^ {8}run: (.*\n(?: {10,}.*\n)*)", workflow, re.M)
        self.assertGreaterEqual(len(blocks), 6)
        for block in blocks:
            self.assertNotIn("${{", block)
        for name in ("CLIP_SECONDS", "THREADS", "LONG_CLIP_SECONDS"):
            self.assertRegex(workflow, rf"\n {{10}}{name}: \$\{{\{{ inputs\.[a-z_]+ \|\| '[0-9 ]+' \}}\}}\n")
        self.assertRegex(workflow, r'--clip-seconds "\$CLIP_SECONDS"')
        ci = (MEASURE.ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertNotIn("ubuntu-24.04-arm", ci)


if __name__ == "__main__":
    unittest.main()
