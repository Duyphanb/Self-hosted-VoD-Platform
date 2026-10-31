#!/bin/sh
set -eu
umask 077
step="configuration validation"
fail() { echo "Storage initialization failed: $step" >&2; exit 1; }
trap 'fail' HUP INT TERM
for bucket in "$MINIO_BUCKET_RAW" "$MINIO_BUCKET_HLS" "$MINIO_BUCKET_THUMBNAILS"; do
  case "$bucket" in ''|*[!a-z0-9.-]*) fail;; esac
done
[ "$MINIO_BUCKET_RAW" != "$MINIO_BUCKET_HLS" ] || fail
[ "$MINIO_BUCKET_RAW" != "$MINIO_BUCKET_THUMBNAILS" ] || fail
[ "$MINIO_BUCKET_HLS" != "$MINIO_BUCKET_THUMBNAILS" ] || fail
[ "$MINIO_ACCESS_KEY" != "$MINIO_BACKEND_ACCESS_KEY" ] || fail
[ "$MINIO_ACCESS_KEY" != "$MINIO_WORKER_ACCESS_KEY" ] || fail
[ "$MINIO_BACKEND_ACCESS_KEY" != "$MINIO_WORKER_ACCESS_KEY" ] || fail
[ "$MINIO_SECRET_KEY" != "$MINIO_BACKEND_SECRET_KEY" ] || fail
[ "$MINIO_SECRET_KEY" != "$MINIO_WORKER_SECRET_KEY" ] || fail
[ "$MINIO_BACKEND_SECRET_KEY" != "$MINIO_WORKER_SECRET_KEY" ] || fail
attempts=0
step="authentication or endpoint unavailable"
# mc reads both keys from non-terminal stdin; secrets must never enter argv.
until printf '%s\n%s\n' "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" |
  mc alias set local http://minio:9000 >/dev/null 2>&1; do
  attempts=$((attempts + 1))
  if [ "$attempts" -ge 60 ]; then
    echo "Storage initialization failed: authentication or endpoint unavailable" >&2
    exit 1
  fi
  sleep 2
done
step="private bucket provisioning"
for bucket in "$MINIO_BUCKET_RAW" "$MINIO_BUCKET_HLS" "$MINIO_BUCKET_THUMBNAILS"; do
  mc mb --ignore-existing "local/$bucket" >/dev/null 2>&1 || fail
  mc anonymous set none "local/$bucket" >/dev/null 2>&1 || fail
done
cat > /tmp/backend-policy.json <<EOF
{"Version":"2012-10-17","Statement":[
{"Effect":"Deny","Action":["admin:CreateServiceAccount","admin:ChangeMyPassword"]},
{"Effect":"Allow","Action":["s3:PutObject","s3:DeleteObject","s3:AbortMultipartUpload"],"Resource":["arn:aws:s3:::$MINIO_BUCKET_RAW/*"]},
{"Effect":"Allow","Action":["s3:GetObject"],"Resource":["arn:aws:s3:::$MINIO_BUCKET_HLS/video-assets/*/attempt-*/*"]}]}
EOF
cat > /tmp/worker-policy.json <<EOF
{"Version":"2012-10-17","Statement":[
{"Effect":"Deny","Action":["admin:CreateServiceAccount","admin:ChangeMyPassword"]},
{"Effect":"Allow","Action":["s3:GetObject"],"Resource":["arn:aws:s3:::$MINIO_BUCKET_RAW/*"]},
{"Effect":"Allow","Action":["s3:PutObject","s3:DeleteObject","s3:AbortMultipartUpload"],"Resource":["arn:aws:s3:::$MINIO_BUCKET_HLS/video-assets/*/attempt-*/*"]}]}
EOF
step="backend user provisioning"
printf '%s\n' "$MINIO_BACKEND_SECRET_KEY" |
  mc admin user add local "$MINIO_BACKEND_ACCESS_KEY" >/dev/null 2>&1 || fail
step="worker user provisioning"
printf '%s\n' "$MINIO_WORKER_SECRET_KEY" |
  mc admin user add local "$MINIO_WORKER_ACCESS_KEY" >/dev/null 2>&1 || fail
step="scoped policy provisioning"
mc admin policy create local vod-backend /tmp/backend-policy.json >/dev/null 2>&1 || fail
mc admin policy create local vod-worker /tmp/worker-policy.json >/dev/null 2>&1 || fail
mc admin policy attach local vod-backend --user "$MINIO_BACKEND_ACCESS_KEY" >/dev/null 2>&1 || fail
mc admin policy attach local vod-worker --user "$MINIO_WORKER_ACCESS_KEY" >/dev/null 2>&1 || fail
rm -f /tmp/backend-policy.json /tmp/worker-policy.json
echo "Private storage and scoped identities initialized"
