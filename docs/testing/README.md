# Testing Docs

Use this folder for verification evidence and hardening guides.

## Planned Contents

- manual E2E checklist
- Postman collection notes
- Newman run plan
- smoke test checklist
- known limitations
- test evidence index

## Current Hardening Guides

- [Sprint 2 Auth closeout](SPRINT-2-CLOSEOUT.md)
- [Dependency security](DEPENDENCY-SECURITY.md)
- [Upload transport/storage rehearsal](UPLOAD-REHEARSAL.md)

## Upload edge checks

Run `python -m unittest discover -s deploy/tests -p 'test_*.py'` from the repository root
for the static route, inheritance and limit-equality checks. With Docker available, run
`python deploy/tests/nginx-edge-smoke.py`; it creates and removes its own
`vod-auth-audit-*` network and containers. No application JVM or existing Compose volumes
are used. It checks Nginx syntax, Content-Length/chunked streaming, route body limits,
edge JSON errors, unchanged backend error bodies, and HLS/health routing.
Scratch files use the system temporary directory and are removed on exit. For a
Docker Desktop bind mount, set `VOD_SMOKE_TMP` to an existing host directory that
Docker can access (for example a temporary directory on D: on Windows). Only the
smoke's generated child directory is removed; the supplied parent is retained.

`sh deploy/tests/production-config-smoke.sh` checks the production preflight separately,
including rejection of an upload limit that differs from the frozen 2048 MiB edge limit.
The regular CI `Skeleton build checks` job runs all three checks. The manual synthetic
2,000,000,000-byte rehearsal is **not VPS evidence**; actual VPS measurements and concurrent
upload headroom remain the public-deployment gate in
[#180](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/180).

## Purpose

- prove the upload -> transcode -> playback flow
- map tests back to requirements and architecture
- document manual checks that are not automated yet
- support local and VPS demo readiness

## Boundary

Do not put requirements or architecture source-of-truth here. Link back to `docs/requirements/` and `docs/architecture/` when needed.
