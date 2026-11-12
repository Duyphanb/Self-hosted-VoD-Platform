#!/bin/sh
# Issue 5.8 (ADR-010 D25): checks a built worker runtime image.
#   sh deploy/tests/worker-image-check.sh <image>
# - the default user is not root (id -u);
# - the installed ffmpeg is at least the floor recorded in worker/Dockerfile (dpkg --compare-versions);
# - logs the installed ffmpeg, ffprobe and OpenJDK versions for the pull request record.
# It runs only local tools inside the image, with no network.

set -eu

image="${1:?usage: worker-image-check.sh <image>}"
root="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"

floor="$(sed -n 's/^ARG FFMPEG_MIN_VERSION=\([^[:space:]]*\)[[:space:]]*$/\1/p' "$root/worker/Dockerfile")"
case "$floor" in
  ''|*' '*) printf '%s\n' "FAIL worker/Dockerfile records no single FFMPEG_MIN_VERSION" >&2; exit 1 ;;
esac

uid="$(docker run --rm --network none --entrypoint id "$image" -u)"
gid="$(docker run --rm --network none --entrypoint id "$image" -g)"
if [ "$uid" = "0" ] || [ "$gid" = "0" ]; then
  printf '%s\n' "FAIL worker image runs as root (uid $uid, gid $gid)" >&2
  exit 1
fi
printf '%s\n' "PASS worker image default user is non-root (uid $uid, gid $gid)"

installed="$(docker run --rm --network none --entrypoint dpkg-query "$image" -W -f='${Version}' ffmpeg)"
if docker run --rm --network none --entrypoint dpkg "$image" --compare-versions "$installed" ge "$floor"; then
  printf '%s\n' "PASS ffmpeg $installed is at least the recorded floor $floor"
else
  printf '%s\n' "FAIL ffmpeg $installed is below the recorded floor $floor" >&2
  exit 1
fi

docker run --rm --network none --entrypoint dpkg-query "$image" -W ffmpeg openjdk-21-jre-headless
docker run --rm --network none --entrypoint sh "$image" -c 'ffmpeg -version | head -n 1 && ffprobe -version | head -n 1'
