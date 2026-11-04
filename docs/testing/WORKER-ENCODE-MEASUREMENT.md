# Worker encode measurement (#124)

Status: **template. No arm64 or A1 VPS measurement is recorded yet.** Every `_pending_` cell is filled
from a run artifact, with its link, and nothing here is a measured value until then. The A1 VM part is the
owner's and is not done (the Oracle account is pending).

ADR-010 D27 and D28 and INFRASTRUCTURE [Upload and encoding settings](../architecture/INFRASTRUCTURE.md#upload-and-encoding-settings)
give [#124](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/124) the FFprobe and FFmpeg deadlines,
the review of `WORKER_FFMPEG_THREADS` and, with #180, the calibration of the D27 output budget and disk
reserve. The issue adds the JVM and worker memory sizing. Issues 5.4, 5.5, 5.8, #74 and #103 take their
values from this record once the owner accepts them. Runner timings are not an NFR-002 baseline, and no
encode-time NFR follows from them.

## What runs

`deploy/tests/worker-encode-measurement.py` runs FFprobe and the Issue 5.5 command line inside the ADR-010
D25 worker image (the final stage of `worker/Dockerfile`). It refuses an image whose architecture differs from
the Docker host, so it never measures under emulation.

- **Commands.** The encode is the argument list of `ReferenceHlsEncodeFfmpegTests.referenceArguments` (D27
  profile), with `-threads` and `-filter_threads` set to each `WORKER_FFMPEG_THREADS` value. A unit test keeps
  the script's copy equal to the Java reference. The probe is `MediaTools.probe` plus `-max_streams 8`; the D26
  `-codec_whitelist` arrives with Issue 5.4.
- **Limits.** Each probe and encode runs in a fresh container with no network and the worker limits of
  `deploy/docker-compose.prod.yml` (`--cpus 1.00 --memory 1024m`). In production FFmpeg shares that container
  with the worker JVM, so the JVM row below adds to the FFmpeg peaks.
- **Clips.** They are generated at run time at the D26 size caps: 1920x1080, 30 fps, H.264 High 8-bit with AAC
  192 kb/s in a faststart MP4. No media is committed.
  - *High-entropy*: gray with full-strength uniform temporal noise and white-noise audio, 40 Mb/s video.
  - *Typical*: the `testsrc2` pattern with light grain and a sine tone, 8 Mb/s video. It is synthetic, not
    natural footage.
  - Durations of 2, 20 and 60 s, each with `WORKER_FFMPEG_THREADS` 1 and 2.
  - Probe-only files of the 10800 s cap: a 10 s black segment repeated by stream copy, as MP4 and Matroska, so
    the probe reads a cap-length index.
  - Opt-in long clip (`--long-clip-seconds`, or the `long_clip_seconds` dispatch input): high-entropy, at the
    average rate a 2048 MiB upload of that length can have. It is encoded once, with `--long-clip-threads`
    (the `long_clip_threads` input, default 1).
- **Recorded per run.**
  - FFprobe and FFmpeg wall time, measured inside the container, so container start-up is excluded.
  - The output size and its ADR-010 D27 checks; a run whose output fails them fails the measurement.
  - The temp-disk peak: the raw copy plus the HLS directory, polled every 0.5 s.
  - `docker stats` peaks: CPU %, MemUsage (the cgroup usage minus inactive file cache) and PIDs.
  - The cgroup's own `memory.peak`, `pids.peak`, CPU throttling (`cpu.stat`) and OOM events. `memory.peak`
    includes page cache, such as the HLS segments just written, so it can exceed what FFmpeg needs.
  - FFmpeg sizes its automatic threads, such as the decoder's, from the CPUs it may run on, not from the
    `--cpus` quota, so the PIDs and memory peaks also depend on the host's CPU count, which the run identity
    records. Compare them only between hosts with the same count.
- **Derived.** A least-squares fit per clip type and thread count gives encode seconds per media second (the
  slope) and the fixed overhead (the intercept). Output bytes are fitted too, because the VBV buffer lets a
  clip's first seconds exceed the maxrate: a 2 s clip's kb/s overstates the steady rate. Values at 10800 s
  come from these fits and are labelled `PREDICTED` in the artifact. The `Record rows` section of the
  artifact's `summary.md` gives the rows of the tables below, in their columns and units.

`deploy/tests/jvm-limits-smoke.py` starts the backend and worker with their dependencies under the production
overlay in a fresh, disposable Compose project with generated credentials. For each JVM it checks:

- the `JAVA_TOOL_OPTIONS` the container received, and that the main JVM picked them up;
- the heap, GC and `ExitOnOutOfMemoryError` flags of a JVM started in the same container and cgroup;
- the applied memory and CPU limits;
- idle `docker stats` memory below the limit.

It removes only its own project's containers, networks and volumes.

## How to run

- **CI (arm64 runner):** the `arm64 worker` workflow (`.github/workflows/arm64-worker.yml`) runs on
  `ubuntu-24.04-arm` for pull requests, pushes to `main` and `workflow_dispatch`. It is not a required check.
  It builds the backend, worker and frontend images natively and records the worker's media package versions.
  It then runs the FFmpeg-tagged tests, the JVM smoke, `storage-runtime-smoke.py` with the pinned Silo image
  (ADR-004 ran Silo only on amd64), and the measurement. Results go to the artifact
  `arm64-worker-measurement-not-vps-evidence`. A dispatch can change the durations and thread counts and add
  the long clip. A cap-length high-entropy encode is likely to outlast the 6-hour hosted-job limit: local
  amd64 dry runs, which are not evidence, took about 3.5 to 7 encode seconds per media second for 2 to 6 s
  high-entropy clips at one CPU, so 10800 s would take 10 hours or more. The cap length therefore stays
  opt-in, and the default run extrapolates.
- **A1 VM (owner):** build the worker image on the VM and run the measurement there. For example:
  `python3 deploy/tests/worker-encode-measurement.py --image <worker image> --output-dir <dir> --long-clip-seconds 10800`.
  Run the JVM smoke with a fresh project name. Check free disk first: a cap-length run needs about 11 GB. If
  `/tmp` is a tmpfs, pass `--work-dir` on the disk that will hold `WORKER_TEMP_DIR`, which is disk-backed.
- **Evidence label:** each report states where it ran. A run on a GitHub-hosted runner is runner evidence, not
  A1 VPS evidence; any other run counts as A1 VPS evidence only when it ran on the A1 VM.
- **Local dry run:** allowed on amd64 to check the scripts. Its numbers are not evidence, and arm64 is never
  emulated locally.
- **Clean-up:** the measurement removes its containers and work directory, and the JVM smoke its project's
  containers, networks and volumes. Neither removes images: on a VM or workstation, remove the worker image
  and the `<project>-backend` and `<project>-worker` images the JVM smoke built.

## Record

### Run identity

| Field | Value |
| --- | --- |
| Runner or VM, CPU model, cores, kernel | _pending_ |
| Workflow run and artifact link | _pending_ |
| Commit | _pending_ |
| Worker image: architecture, ffmpeg, libx264, openjdk-21-jre-headless | _pending_ |

### FFprobe

| Target | Demuxer | Media s | Worst probe s (of 3) |
| --- | --- | --- | --- |
| Generated clips (2, 20, 60 s, both types) | mov | 2-60 | _pending_ |
| `cap-probe.mp4` | mov | 10800 | _pending_ |
| `cap-probe.mkv` | matroska | 10800 | _pending_ |

### FFmpeg reference encode (measured)

| Clip type | Threads | Encode s per media s | Fixed overhead s | Steady output kb/s | Largest temp-disk peak | CPU % peak | MemUsage peak | `memory.peak` | PIDs peak |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| High-entropy | 1 | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ |
| High-entropy | 2 | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ |
| Typical | 1 | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ |
| Typical | 2 | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ |
| Long high-entropy (opt-in) | _pending_ | _pending_ | n/a | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ |

### At the 10800 s cap (predicted, not measured)

| Clip type, threads | Encode duration | HLS output | Temp-disk peak with a 2048 MiB raw copy |
| --- | --- | --- | --- |
| High-entropy, 1 | _pending_ | _pending_ | _pending_ |
| High-entropy, 2 | _pending_ | _pending_ | _pending_ |
| Typical, 1 | _pending_ | _pending_ | _pending_ |
| Typical, 2 | _pending_ | _pending_ | _pending_ |

For comparison, the D27 budget at the cap is B = ceil(10800 × 5000 × 1000 / 8) + 16 MiB, about 6.3 GiB.
The free space required after probing is then 5120 MiB + max(B, 2B - R): about 15.6 GiB with a 2048 MiB raw copy.

### JVMs under the production overlay

| Service | `JAVA_TOOL_OPTIONS` | Memory limit | Max heap (`MaxHeapSize`) | GC | Exit on OOM | Idle MemUsage |
| --- | --- | --- | --- | --- | --- | --- |
| backend, arm64 | `-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError` | 768 MiB | _pending_ | _pending_ | _pending_ | _pending_ |
| worker, arm64 | `-XX:MaxRAMPercentage=30 -XX:+ExitOnOutOfMemoryError` | 1024 MiB | _pending_ | _pending_ | _pending_ | _pending_ |

Two local amd64 runs of the JVM smoke (Docker Desktop, 2026-11-03 and 2026-11-04) are not arm64 or VPS
evidence. They found:

- **Backend:** a 500 MiB `MaxHeapSize` (`Runtime.maxMemory()` 483 MiB) instead of the earlier 25% default
  (about 185 MiB), SerialGC with one CPU, `ExitOnOutOfMemoryError` true, and an idle MemUsage of 307 to
  362 MiB.
- **Worker:** a 308 MiB `MaxHeapSize` (298 MiB), SerialGC, `ExitOnOutOfMemoryError` true, and an idle
  MemUsage of 145 to 162 MiB.

### Silo on arm64

`storage-runtime-smoke.py` with the pinned Silo image on the arm64 runner: _pending_.

## Proposed values (owner decision, pending the arm64 record)

The deadline is `max(WORKER_FFMPEG_TIMEOUT_MIN_SECONDS, WORKER_FFMPEG_TIMEOUT_FACTOR × duration)` (ADR-010 D6-D10).
The owner adds the margin; none is chosen here.

| Setting | Derived from | Proposed | Owner decision |
| --- | --- | --- | --- |
| `WORKER_FFPROBE_TIMEOUT_SECONDS` | Worst probe time, including the cap-length files | _pending_ | _pending_ |
| `WORKER_FFMPEG_TIMEOUT_MIN_SECONDS` | Fixed overhead and the short-clip encode times | _pending_ | _pending_ |
| `WORKER_FFMPEG_TIMEOUT_FACTOR` | Steepest fitted encode seconds per media second (high-entropy); a short clip's own ratio also contains the fixed overhead | _pending_ | _pending_ |
| #74 consumer timeout | Above the probe timeout + the deadline at 10800 s + storage time | _pending_ | _pending_ |
| `WORKER_OUTPUT_BUDGET_KBPS=5000` | Steady output rate and short-clip bursts against B | _pending_ | _pending_ |
| `WORKER_DISK_RESERVE_MB=5120` | Temp-disk peaks; the VPS disk size comes from #180 | _pending_ | _pending_ |
| `WORKER_FFMPEG_THREADS` (1 or 2) | Encode speed of 1 against 2 under the 1-CPU limit | _pending_ | _pending_ |
| Worker memory limit and JVM share | Worker JVM idle memory plus the FFmpeg MemUsage peak against 1024 MiB (`memory.peak` as an upper bound with page cache); PIDs for the 5.8 `pids_limit` | _pending_ | _pending_ |
| Head-of-line blocking | Predicted cap-length encode duration with one worker at concurrency 1 | n/a | Owner accepts or rejects |

## Not covered here

- Provisioning an A1 instance and the A1 measurement (owner; the account is pending).
- The R-003 update, which needs owner approval.
- The #103 reference to this record, made when the record is filled.
