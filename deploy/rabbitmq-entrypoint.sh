#!/bin/sh
# Broker identities (#106) come from definitions that RabbitMQ imports at every
# boot. The bootstrap administrator keeps full access for operator maintenance;
# the backend and worker get non-administrator users whose permissions match
# only the encoding queue topology (ADR-010). RabbitMQ skips its default-user
# seeding when definitions are configured, so this file is the only source of
# users. Secrets go to a tmpfs file readable by the broker user only, never to
# argv or logs; failures name the step, not a value.
set -eu
umask 077
step="configuration validation"
fail() { echo "Broker identity initialization failed: $step" >&2; exit 1; }
definitions_dir=/run/vod-rabbitmq
definitions="$definitions_dir/definitions.json"
config=/etc/rabbitmq/conf.d/90-vod-definitions.conf

require_value() {
  case "$1" in *[![:space:]]*) ;; *) fail ;; esac
  case "$1" in *[[:cntrl:]]*) fail ;; esac
}
# JSON string body; printf is a shell builtin, so values never enter argv.
json() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
regex() { printf '%s' "$1" | sed 's/\./\\./g'; }

admin_user="${RABBITMQ_DEFAULT_USER:-}"
admin_password="${RABBITMQ_DEFAULT_PASS:-}"
backend_user="${RABBITMQ_BACKEND_USERNAME:-}"
backend_password="${RABBITMQ_BACKEND_PASSWORD:-}"
worker_user="${RABBITMQ_WORKER_USERNAME:-}"
worker_password="${RABBITMQ_WORKER_PASSWORD:-}"
queue="${RABBITMQ_ENCODING_QUEUE:-}"
for value in "$admin_user" "$admin_password" "$backend_user" "$backend_password" "$worker_user" "$worker_password"
do
  require_value "$value"
done
[ "$admin_user" != "$backend_user" ] || fail
[ "$admin_user" != "$worker_user" ] || fail
[ "$backend_user" != "$worker_user" ] || fail
[ "$admin_password" != "$backend_password" ] || fail
[ "$admin_password" != "$worker_password" ] || fail
[ "$backend_password" != "$worker_password" ] || fail
# Same rule as EncodingQueueTopology.validateQueueName.
case "$queue" in ''|*[!A-Za-z0-9._-]*|amq.*) fail ;; esac
[ "${#queue}" -le 251 ] || fail

# Both applications declare the identical topology. AMQP requires configure on
# every declared entity, read on the source queue and write on the dead-letter
# exchange for a queue declared with x-dead-letter-exchange, and write on the
# DLQ plus read on the DLX for the binding. Only the backend may publish
# (default exchange); only these entity names match.
source_queue=$(regex "$queue")
dead_letter_exchange=$(regex "$queue.dlx")
dead_letter_queue=$(regex "$queue.dlq")
topology="^($source_queue|$dead_letter_exchange|$dead_letter_queue)\$"
declaration_read="^($source_queue|$dead_letter_exchange)\$"
backend_write="^(amq\\.default|$dead_letter_exchange|$dead_letter_queue)\$"
worker_write="^($dead_letter_exchange|$dead_letter_queue)\$"

step="definitions rendering"
mkdir -p "$definitions_dir" || fail
cat > "$definitions" <<EOF || fail
{"vhosts":[{"name":"/"}],
"users":[
{"name":"$(json "$admin_user")","password":"$(json "$admin_password")","tags":["administrator"]},
{"name":"$(json "$backend_user")","password":"$(json "$backend_password")","tags":[]},
{"name":"$(json "$worker_user")","password":"$(json "$worker_password")","tags":[]}],
"permissions":[
{"user":"$(json "$admin_user")","vhost":"/","configure":".*","write":".*","read":".*"},
{"user":"$(json "$backend_user")","vhost":"/","configure":"$(json "$topology")","write":"$(json "$backend_write")","read":"$(json "$declaration_read")"},
{"user":"$(json "$worker_user")","vhost":"/","configure":"$(json "$topology")","write":"$(json "$worker_write")","read":"$(json "$declaration_read")"}]}
EOF
printf 'load_definitions = %s\n' "$definitions" > "$config" || fail
chown rabbitmq:rabbitmq "$definitions_dir" "$definitions" "$config" || fail
chmod 700 "$definitions_dir" || fail
umask 022
[ "$#" -gt 0 ] || set -- rabbitmq-server
exec docker-entrypoint.sh "$@"
