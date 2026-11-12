#!/bin/sh
# Runs the FFmpeg-tagged worker tests (#123) in the worker image's ffmpeg-test stage, so they use the
# runtime image's own ffmpeg/ffprobe (ADR-010 D25), then requires evidence that every *FfmpegTests class
# executed with no skipped, failed or errored test.
#   WORKER_FFMPEG_TEST_IMAGE  image tag to build (default vod-worker-ffmpeg-test)
#   WORKER_FFMPEG_TEST_CONTAINER  explicit name for the disposable test container (optional)
#   WORKER_FFMPEG_TEST_M2     host Maven cache to mount (default ~/.m2)
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
image="${WORKER_FFMPEG_TEST_IMAGE:-vod-worker-ffmpeg-test}"
m2="${WORKER_FFMPEG_TEST_M2:-$HOME/.m2}"
mkdir -p "$m2"
worker_mount="$root/worker"
m2_mount="$m2"
# On Linux, run as the caller so target/ stays writable by the host build.
user="$(id -u):$(id -g)"
if pwd -W >/dev/null 2>&1; then
    # Git Bash on Windows: give Docker Windows paths and stop MSYS rewriting the mount specs. Docker
    # Desktop maps bind-mount ownership itself and refuses a non-root uid overwriting host files.
    export MSYS_NO_PATHCONV=1
    worker_mount=$(cd "$root/worker" && pwd -W)
    m2_mount=$(cd "$m2" && pwd -W)
    user="0:0"
fi

# Stale reports must not count as evidence.
rm -f "$root"/worker/*/target/surefire-reports/TEST-*FfmpegTests.xml

docker build --target ffmpeg-test -t "$image" "$worker_mount"
set --
if [ -n "${WORKER_FFMPEG_TEST_CONTAINER:-}" ]; then
    set -- --name "$WORKER_FFMPEG_TEST_CONTAINER"
fi
docker run --rm "$@" --user "$user" -e HOME=/tmp -e MAVEN_OPTS=-Duser.home=/tmp \
    -v "$worker_mount:/workspace" -v "$m2_mount:/m2" -w /workspace "$image" \
    mvn -B -ntp -Dmaven.repo.local=/m2/repository -Pffmpeg-tests test

python3 - "$worker_mount" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

worker = pathlib.Path(sys.argv[1])
classes = []
for source in sorted(worker.glob('*/src/test/java/**/*FfmpegTests.java')):
    module = source.relative_to(worker).parts[0]
    name = '.'.join(source.relative_to(worker / module / 'src/test/java').with_suffix('').parts)
    classes.append((module, name))
assert classes, 'no *FfmpegTests classes found'
for module, name in classes:
    report = worker / module / 'target/surefire-reports' / f'TEST-{name}.xml'
    assert report.is_file(), f'{name} did not run'
    suite = ET.parse(report).getroot()
    assert int(suite.attrib['tests']) > 0, f'{name} executed no tests'
    for key in ('skipped', 'failures', 'errors'):
        assert int(suite.attrib[key]) == 0, f'{name} has {key}'
    print(f"{name}: {suite.attrib['tests']} FFmpeg tests passed")
PY
