# Synthetic upload rehearsal

After the upload-edge PR merges, dispatch **Synthetic 2 GB upload rehearsal** on
`main` from GitHub Actions. The workflow has only `workflow_dispatch`, accepts no
deployment target or credentials, and refuses a branch other than `main`.

The result is labelled **synthetic 2,000,000,000-byte transport/storage rehearsal — not VPS evidence**.
The target-VPS measurement and concurrent-upload headroom decision remain
[#180](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/180), which blocks
public deployment. No `limit_conn` decision is inferred from a GitHub runner.

The script (`deploy/tests/upload-rehearsal.py`) creates a fresh
`vod-auth-audit-rehearsal-*` Compose project with generated credentials and the
production overlay. It builds backend and frontend sequentially before starting
services. The production preflight remains enabled; the disposable edge binds
`0.0.0.0:18088` to satisfy its production binding rule. Only the edge publishes
ports. This runs on an ephemeral GitHub-hosted runner, not the 2 GB WSL VM.
Storage provenance is checked with the existing verification script before image
use. The worker is deliberately not started: this is an upload/storage exercise,
and the synthetic input is not a decodable movie.

The runtime fixture is exactly 2,000,000,000 bytes (decimal GB), with `ftyp` at
offset 4 and zeros afterwards. It is sparse; the report gives both logical and
allocated fixture sizes. Sparse allocation and compressible content mean this
cannot demonstrate worst-case physical disk use for real media. It passes the
API's signature-family check and fits below the 2048 MiB multipart request limit.
The script registers an account, grants the documented first-administrator role,
seeds a DRAFT movie, and streams multipart through Nginx. It requires 202, the
persisted QUEUED asset/job pair and an exact 2,000,000,000-byte raw object in the
`vod-raw` bucket. Bucket privacy is proven by `storage-runtime-smoke.py` in CI,
not by this rehearsal: its `--verify-only` invocation checks provenance/config,
and the root-authenticated object-size check does not establish privacy.

The Step Summary and JSON/Markdown artifact contain:

- client send time, total client time, and the wait from the final multipart byte
  handed to the client TCP stack until the complete response is read;
- observed spool logical/allocated peaks, per-container `docker stats` MemUsage
  peaks, and separate cgroup v2 `memory.stat` `anon`/`file` observed peaks;
- timestamped samples, minimum disk free space, and disk use before/after build,
  stack startup and upload, including the Docker data root;
- logical versus allocated fixture size, commit, result, and cleanup result.

MemUsage includes active page cache, is bounded by the container memory limit,
and is neither JVM heap nor process RSS. Spool writes and reads can therefore
drive it toward the limit without corresponding JVM heap growth. The separate
`anon` and `file` counters describe cgroup anonymous memory and file cache;
their independent observed peaks need not occur in the same sample. If cgroup
v2 `memory.stat` is unavailable (for example on cgroup v1), the report explicitly
marks these counters unavailable rather than inventing zero values. Malformed
available counters or failed probes still fail the measurement.

Polling may miss shorter peaks; reading each container's cgroup counters also
adds sampling overhead. Saving wait is a client-side measurement; it does not
establish when the final body byte reaches Tomcat. A zero/missing spool observation or incomplete measurement
fails the run instead of claiming evidence. Docker/kernel metadata are measured
on the runner; encoding, ARM throughput, WAN transfer and VPS capacity are outside
this evidence.

Free space is checked before builds and again before startup/upload for the raw
object, spool and a safety reserve; a failed precheck stops without uploading.
If the runner user cannot traverse the Docker data root, the script uses a
read-only `sudo -n df -B1 --output=size,used,avail -- <DockerRootDir>` probe on that
exact path, with argument-list execution. A denied/invalid probe fails closed;
it never substitutes a parent directory or assumes a different filesystem has
the same free space.
No host-wide prune is performed. The script always attempts `compose down
--volumes`; the workflow has an independent cleanup step that rechecks exact
Compose project labels before removing only that project's containers, networks
and volumes. The generated fixture is removed. Captured command/HTTP/SQL output,
tokens, account credentials, object names and storage keys are not artifacts.

This workflow is added as executable tooling. Its actual 2 GB measurement must be
dispatched after merge; adding or parsing the workflow is not a completed run.
