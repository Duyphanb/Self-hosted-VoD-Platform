#!/usr/bin/env python3
"""Production-overlay JVM limit smoke (#124) on a fresh, disposable Compose project.

Builds and starts the backend and worker, with their dependencies, under deploy/docker-compose.prod.yml and
generated credentials. For each JVM it records the JAVA_TOOL_OPTIONS the container received, that the main
JVM picked them up, the heap, GC and OutOfMemoryError flags of a JVM started in the same container (same
environment and cgroup), the container limits and sampled docker stats. It fails unless the options match the
overlay, the heap follows -XX:MaxRAMPercentage of the memory limit and memory stays below that limit.

Command output is captured and only parsed fields are kept, so credentials are never printed or written. The
project must not exist yet; afterwards only that project's containers, networks and volumes are removed.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
LABEL = "Production-overlay JVM limits (#124)"
SERVICES = ("backend", "worker")
PROJECT_PATTERN = re.compile(r"[a-z0-9][a-z0-9_-]{0,62}")
HEAP_TOLERANCE = 0.03
# The JVM rounds MaxHeapSize up to its heap alignment, 512 times the page size: 2 MiB with 4 KiB pages, but
# 32 MiB with the 64 KiB pages some arm64 kernels use (the worker's 307.2 MiB target then becomes 320 MiB).
HEAP_ALIGNMENT_ALLOWANCE = 32 * 1024**2
STATS_SAMPLES = 8


class SmokeError(RuntimeError):
    """A fixed diagnostic; never built from captured command output."""


def command(args, *, env=None, timeout=120, check=True):
    try:
        result = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8",
                                errors="replace", timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise SmokeError("A smoke command could not finish: " + " ".join(args[:3])) from None
    if check and result.returncode:
        raise SmokeError("A smoke command failed (output withheld): " + " ".join(args[:4]))
    return result


def evidence():
    """What a report is evidence of. The A1 VM runs this script too, so a fixed runner label would be wrong."""
    if os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted":
        return "GitHub-hosted runner: runner evidence, not A1 VPS evidence"
    return "Not a GitHub-hosted runner: A1 VPS evidence only when run on the A1 VM; a local run is not evidence"


def validate_project(project):
    if not PROJECT_PATTERN.fullmatch(project) or project == "vod-platform":
        raise SmokeError("Use a fresh, dedicated Compose project name")


def project_resources(project):
    validate_project(project)
    label = "label=com.docker.compose.project=" + project
    return {resource: command(["docker", resource, "ls", "-q" if resource != "container" else "-aq",
                               "--filter", label]).stdout.split()
            for resource in ("container", "network", "volume")}


def cleanup_project(project):
    """Removes only resources whose exact Compose project label is this project."""
    for resource, removal in (("container", ["-f", "-v"]), ("network", []), ("volume", [])):
        for resource_id in project_resources(project)[resource]:
            template = ('{{index .Config.Labels "com.docker.compose.project"}}' if resource == "container"
                        else '{{index .Labels "com.docker.compose.project"}}')
            owner = command(["docker", resource, "inspect", "--format", template, resource_id]).stdout.strip()
            if owner != project:
                raise SmokeError("Cleanup ownership check failed")
            command(["docker", resource, "rm", *removal, resource_id])


def compose_variables():
    names = set()
    for name in ("docker-compose.yml", "docker-compose.prod.yml"):
        names |= set(re.findall(r"\$\{([A-Za-z_][A-Za-z0-9_]*)", (ROOT / "deploy" / name).read_text(encoding="utf-8")))
    return names


def new_environment():
    """Generated credentials and overlay defaults only: no variable either Compose file reads is inherited,
    so a developer's deployment settings or JVM overrides cannot leak in. Tool variables (PATH, the Docker
    CLI's plugin and config locations) are kept."""
    excluded = {name.upper() for name in compose_variables()} | {"DOCKER_DEFAULT_PLATFORM"}
    env = {key: value for key, value in os.environ.items()
           if key.upper() not in excluded and not key.upper().startswith("COMPOSE_")}
    for prefix in ("POSTGRES", "RABBITMQ"):
        env[prefix + ("_USER" if prefix == "POSTGRES" else "_USERNAME")] = "audit_" + secrets.token_hex(8)
        env[prefix + "_PASSWORD"] = secrets.token_urlsafe(32)
    for prefix in ("MINIO", "MINIO_BACKEND", "MINIO_WORKER"):
        env[prefix + "_ACCESS_KEY"] = "audit_" + secrets.token_hex(12)
        env[prefix + "_SECRET_KEY"] = secrets.token_urlsafe(48)
    # Nginx is not started; the preflight still requires a non-loopback edge setting.
    env.update({"JWT_SECRET": secrets.token_urlsafe(48), "NGINX_HTTP_PORT": "0.0.0.0:18089",
                "MAX_UPLOAD_SIZE_MB": "2048", "CORS_ALLOWED_ORIGINS": ""})
    return env


def compose_command(project):
    return ["docker", "compose", "-p", project, "--env-file", os.devnull,
            "-f", str(ROOT / "deploy/docker-compose.yml"), "-f", str(ROOT / "deploy/docker-compose.prod.yml")]


def size_bytes(text):
    """Compose memory values and docker stats sizes, such as 805306368, 768M, 768MiB or 1.5GiB."""
    match = re.fullmatch(r"([0-9.]+)\s*([A-Za-z]*)", str(text).strip())
    units = {"": 1, "B": 1, "b": 1, "k": 1024, "K": 1024, "m": 1024**2, "M": 1024**2, "g": 1024**3, "G": 1024**3,
             "kB": 1000, "KB": 1000, "MB": 1000**2, "GB": 1000**3, "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3}
    if not match or match.group(2) not in units:
        raise SmokeError("Unrecognized size")
    return int(float(match.group(1)) * units[match.group(2)])


def expected_settings(config_services):
    """The JVM options and limits the overlay gives each service, without keeping any other configuration."""
    expected = {}
    for service in SERVICES:
        definition = config_services[service]
        options = definition["environment"]["JAVA_TOOL_OPTIONS"]
        percentage = re.findall(r"(?:^|\s)-XX:MaxRAMPercentage=([0-9]+)(?=\s|$)", options)
        if not percentage or "-XX:+ExitOnOutOfMemoryError" not in options.split():
            raise SmokeError("The overlay does not set the #124 JVM options for " + service)
        limits = definition["deploy"]["resources"]["limits"]
        expected[service] = {"java_tool_options": options, "max_ram_percentage": int(percentage[-1]),
                             "memory_limit_bytes": size_bytes(limits["memory"]), "cpus": float(limits["cpus"])}
    return expected


def parse_flags(text):
    """`java -XX:+PrintFlagsFinal -version` rows, e.g. `size_t MaxHeapSize = 524288000 {product} {ergonomic}`."""
    flags = {}
    for line in text.splitlines():
        match = re.match(r"\s*\S+\s+(\w+)\s+:?=\s+(\S*)", line)
        if match:
            flags[match.group(1)] = match.group(2)
    if "MaxHeapSize" not in flags:
        raise SmokeError("PrintFlagsFinal output has no MaxHeapSize")
    return flags


def parse_settings(text):
    """`-XshowSettings:vm` and `-XshowSettings:system` lines the report keeps."""
    patterns = {"max_heap_estimated": r"Max\. Heap Size \(Estimated\):\s*(\S+)",
                "cgroup_provider": r"Provider:\s*(\S+)", "effective_cpu_count": r"Effective CPU Count:\s*(\d+)",
                "memory_limit": r"Memory Limit:\s*(\S+)"}
    found = {}
    for key, pattern in patterns.items():
        match = re.search(pattern, text)
        found[key] = match.group(1) if match else None
    return found


def parse_stats(text, ids):
    by_id = {container[:12]: service for service, container in ids.items()}
    sample = {}
    for line in text.splitlines():
        row = json.loads(line)
        service = by_id.get(row["ID"][:12])
        if service:
            used, limit = row["MemUsage"].split("/", 1)
            sample[service] = {"memory_usage_bytes": size_bytes(used), "memory_limit_bytes": size_bytes(limit),
                               "cpu_percent": float(row["CPUPerc"].rstrip("%")),
                               "pids": int(row["PIDs"]) if str(row.get("PIDs", "")).isdigit() else None}
    if set(sample) != set(ids):
        raise SmokeError("Incomplete docker stats sample")
    return sample


def check_service(service, expected, observed):
    """Every failed property, as fixed text; an empty list means the JVM runs as the overlay intends."""
    failures = []
    if observed["java_tool_options"] != expected["java_tool_options"]:
        failures.append(service + ": the container did not receive the overlay JAVA_TOOL_OPTIONS")
    if not observed["main_jvm_picked_up_options"]:
        failures.append(service + ": the main JVM did not report picking up JAVA_TOOL_OPTIONS")
    flags = observed["flags"]
    if flags.get("ExitOnOutOfMemoryError") != "true":
        failures.append(service + ": ExitOnOutOfMemoryError is not enabled")
    if float(flags.get("MaxRAMPercentage", "nan")) != expected["max_ram_percentage"]:
        failures.append(service + ": MaxRAMPercentage differs from the overlay")
    if observed["memory_limit_bytes"] != expected["memory_limit_bytes"]:
        failures.append(service + ": the container memory limit differs from the overlay")
    target = expected["memory_limit_bytes"] * expected["max_ram_percentage"] / 100
    highest = max(target * (1 + HEAP_TOLERANCE), target + HEAP_ALIGNMENT_ALLOWANCE)
    if not target * (1 - HEAP_TOLERANCE) <= int(flags["MaxHeapSize"]) <= highest:
        failures.append(service + ": MaxHeapSize does not follow MaxRAMPercentage of the memory limit")
    if not observed["stats"]["memory_usage_peak_bytes"] < expected["memory_limit_bytes"]:
        failures.append(service + ": docker stats memory reached the limit")
    return failures


def observe(service, container, env):
    options = command(["docker", "exec", container, "printenv", "JAVA_TOOL_OPTIONS"], env=env).stdout.strip()
    logs = command(["docker", "logs", container], env=env)
    picked_up = f"Picked up JAVA_TOOL_OPTIONS: {options}" in (logs.stdout + logs.stderr).splitlines()
    flags_run = command(["docker", "exec", container, "java", "-XX:+PrintFlagsFinal", "-version"], env=env, timeout=60)
    flags = parse_flags(flags_run.stdout)
    settings = {}
    for selector in ("vm", "system"):
        result = command(["docker", "exec", container, "java", "-XshowSettings:" + selector, "-version"], env=env,
                         timeout=60)
        settings.update({key: value for key, value in parse_settings(result.stderr + result.stdout).items() if value})
    inspected = [int(value) for value in command(["docker", "inspect", "--format",
                                                  "{{.HostConfig.Memory}} {{.HostConfig.NanoCpus}}", container]).stdout.split()]
    cgroup = command(["docker", "exec", container, "cat", "/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/cpu.max"],
                     env=env, check=False).stdout.split("\n")
    status = command(["docker", "exec", container, "cat", "/proc/1/status"], env=env).stdout
    rss = {key: size_bytes(value.replace(" kB", "k").replace(" ", ""))
           for key, value in re.findall(r"^(VmRSS|VmHWM):\s*(.+)$", status, re.M)}
    keep = ("MaxHeapSize", "InitialHeapSize", "MaxRAMPercentage", "ExitOnOutOfMemoryError", "UseSerialGC",
            "UseG1GC", "UseParallelGC", "ActiveProcessorCount")
    return {"java_tool_options": options, "main_jvm_picked_up_options": picked_up,
            "flags": {key: flags[key] for key in keep if key in flags}, "settings": settings,
            "memory_limit_bytes": inspected[0], "nano_cpus": inspected[1],
            "cgroup_memory_max": cgroup[0].strip() if cgroup else None,
            "cgroup_cpu_max": cgroup[1].strip() if len(cgroup) > 1 else None,
            "main_jvm_rss_bytes": rss.get("VmRSS"), "main_jvm_peak_rss_bytes": rss.get("VmHWM")}


def write_report(output, report):
    output.mkdir(parents=True, exist_ok=True)
    (output / "jvm-limits.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    lines = ["# " + LABEL, "", "Result: **" + report["result"] + "**", "",
             "| Service | JAVA_TOOL_OPTIONS | Memory limit | Max heap | GC | Exit on OOM | MemUsage peak (idle) | "
             "Main JVM RSS |", "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    for service, observed in report.get("services", {}).items():
        flags = observed.get("flags", {})
        gc = next((name[3:-2] for name in ("UseSerialGC", "UseG1GC", "UseParallelGC") if flags.get(name) == "true"), "?")
        mib = lambda value: "n/a" if value is None else f"{value / 1024**2:.0f} MiB"
        lines.append(f"| {service} | `{observed.get('java_tool_options')}` | {mib(observed.get('memory_limit_bytes'))} | "
                     f"{mib(int(flags['MaxHeapSize']) if 'MaxHeapSize' in flags else None)} | {gc} | "
                     f"{flags.get('ExitOnOutOfMemoryError')} | "
                     f"{mib(observed.get('stats', {}).get('memory_usage_peak_bytes'))} | "
                     f"{mib(observed.get('main_jvm_rss_bytes'))} |")
    if report.get("failures"):
        lines += [""] + ["- " + failure for failure in report["failures"]]
    lines += ["", "Idle stack after startup; docker stats MemUsage is the cgroup usage minus inactive file cache.",
              "", "Evidence: " + report.get("evidence", evidence()) + ".", ""]
    summary = "\n".join(lines)
    (output / "summary.md").write_text(summary, encoding="utf-8")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as destination:
            destination.write(summary)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--project", required=True, help="fresh Compose project name owned by this run")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--no-build", action="store_true", help="use images already built for this project")
    args = parser.parse_args()
    validate_project(args.project)
    if any(project_resources(args.project).values()):
        raise SmokeError("The Compose project already exists; choose a fresh project name")
    env = new_environment()
    compose = compose_command(args.project)
    report = {"label": LABEL, "evidence": evidence(), "result": "FAIL",
              "started_at": datetime.now(timezone.utc).isoformat(),
              "commit": os.environ.get("GITHUB_SHA"), "overlay": "deploy/docker-compose.prod.yml",
              "architecture": command(["docker", "info", "--format", "{{.Architecture}}"]).stdout.strip()}
    failure = None
    try:
        report["phase"] = "configuration"
        expected = expected_settings(json.loads(command(compose + ["config", "--format", "json"],
                                                        env=env).stdout)["services"])
        report["expected"] = expected
        if not args.no_build:
            report["phase"] = "image build"
            command(compose + ["build", *SERVICES], env=env, timeout=2400)
        report["phase"] = "startup"
        command(compose + ["up", "-d", "--wait", "--wait-timeout", "300", "--no-build", *SERVICES], env=env, timeout=420)
        ids = {}
        for service in SERVICES:
            ids[service] = command(compose + ["ps", "-q", service], env=env).stdout.strip()
            owner = command(["docker", "inspect", "--format", '{{index .Config.Labels "com.docker.compose.project"}}',
                             ids[service]]).stdout.strip()
            if not re.fullmatch(r"[0-9a-f]{64}", ids[service]) or owner != args.project:
                raise SmokeError(service + " container is missing or not owned by this project")
        report["phase"] = "observation"
        time.sleep(10)  # Let start-up allocation settle; the samples describe the idle stack.
        samples = []
        for _ in range(STATS_SAMPLES):
            stats = command(["docker", "stats", "--no-stream", "--format", "{{json .}}", *ids.values()], timeout=30)
            samples.append(parse_stats(stats.stdout, ids))
        report["services"] = {}
        for service in SERVICES:
            observed = observe(service, ids[service], env)
            rows = [sample[service] for sample in samples]
            observed["stats"] = {"samples": len(rows),
                                 "memory_usage_peak_bytes": max(row["memory_usage_bytes"] for row in rows),
                                 "memory_usage_last_bytes": rows[-1]["memory_usage_bytes"],
                                 "memory_limit_bytes": rows[-1]["memory_limit_bytes"],
                                 "cpu_percent_peak": max(row["cpu_percent"] for row in rows),
                                 "pids_peak": max((row["pids"] or 0) for row in rows)}
            report["services"][service] = observed
        report["failures"] = [failure for service in SERVICES
                              for failure in check_service(service, expected[service], report["services"][service])]
        if report["failures"]:
            raise SmokeError("JVM limits differ from the production overlay")
        report["result"] = "PASS"
        report["phase"] = "complete"
    except SmokeError as error:
        failure = str(error)
    except Exception as error:  # Never echo payloads: they could contain configuration values.
        failure = "Unexpected " + type(error).__name__
    finally:
        command(compose + ["down", "--volumes", "--remove-orphans", "--timeout", "20"], env=env, timeout=180,
                check=False)
        try:
            cleanup_project(args.project)
            report["cleanup"] = "PASS"
        except SmokeError:
            report["cleanup"] = "FAIL"
            failure = failure or "Cleanup failed"
        report["completed_at"] = datetime.now(timezone.utc).isoformat()
        if failure:
            report["failure"] = failure
            report["result"] = "FAIL"
        write_report(args.output_dir, report)
    if failure:
        raise SmokeError(failure)
    print("PASS " + LABEL, flush=True)


if __name__ == "__main__":
    def interrupted(_signum, _frame):
        raise SmokeError("Smoke interrupted")
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        main()
    except SmokeError as error:
        print("FAIL " + str(error), file=sys.stderr)
        sys.exit(1)
