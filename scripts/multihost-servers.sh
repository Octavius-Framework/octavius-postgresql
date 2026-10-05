#!/usr/bin/env bash
# Starts, in Docker, the three servers MultiHostReplicationTest runs against:
#
#   - a primary on MULTIHOST_PRIMARY_PORT (5434),
#   - a streaming standby of it on MULTIHOST_STANDBY_PORT (5435) - the same cluster, so the same
#     system_identifier and the same type OIDs,
#   - a primary of a cluster of its own on MULTIHOST_FOREIGN_PORT (5436), initialised separately.
#
# Each has the test database and credentials of TestDatabase (octavius_test, postgres / 1234).
#
#   scripts/multihost-servers.sh        starts them and waits until the standby is streaming
#   scripts/multihost-servers.sh down   removes them
#
# Then: TEST_MULTIHOST=true ./gradlew :driver:test --tests "*MultiHostReplicationTest*"
set -euo pipefail

IMAGE="${MULTIHOST_IMAGE:-postgres:18}"
NETWORK=octavius-multihost
PRIMARY_PORT="${MULTIHOST_PRIMARY_PORT:-5434}"
STANDBY_PORT="${MULTIHOST_STANDBY_PORT:-5435}"
FOREIGN_PORT="${MULTIHOST_FOREIGN_PORT:-5436}"

down() {
    docker rm -f octavius-mh-primary octavius-mh-standby octavius-mh-foreign >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
}

if [[ "${1:-up}" == "down" ]]; then
    down
    exit 0
fi

down
docker network create "$NETWORK" >/dev/null

# Every server is 5432 inside its container, so each is told apart by cluster_name - what the tests ask a
# session for to learn where it settled.
server() { # name, port, cluster_name
    docker run -d --name "$1" --network "$NETWORK" -p "$2:5432" \
        -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=1234 -e POSTGRES_DB=octavius_test \
        "$IMAGE" -c cluster_name="$3" >/dev/null
}

ready() { # name - the entrypoint's own init server listens on the socket only, so wait for TCP
    timeout 120 bash -c "until docker exec $1 pg_isready -h 127.0.0.1 -U postgres -q; do sleep 1; done"
}

server octavius-mh-primary "$PRIMARY_PORT" primary
server octavius-mh-foreign "$FOREIGN_PORT" foreign
ready octavius-mh-primary

# A role to stream as, and a rule letting it in from the network: the image's pg_hba only lets
# replication in over the loopback.
docker exec octavius-mh-primary psql -q -U postgres -c "CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'replica'"
docker exec octavius-mh-primary bash -c 'echo "host replication replicator all scram-sha-256" >> "$PGDATA/pg_hba.conf"'
docker exec octavius-mh-primary psql -q -U postgres -c "SELECT pg_reload_conf()" >/dev/null

# The standby is a base backup of the primary, written with -R so it starts as a standby following it.
docker run -d --name octavius-mh-standby --network "$NETWORK" -p "$STANDBY_PORT:5432" \
    --user postgres -e PGPASSWORD=replica "$IMAGE" bash -c '
        until pg_basebackup -h octavius-mh-primary -U replicator -D /tmp/standby -R -X stream -c fast; do
            rm -rf /tmp/standby; sleep 1
        done
        exec postgres -D /tmp/standby -c listen_addresses="*" -c cluster_name=standby
    ' >/dev/null

ready octavius-mh-standby
ready octavius-mh-foreign

timeout 60 bash -c 'until [[ "$(docker exec octavius-mh-primary psql -U postgres -tAc "SELECT count(*) FROM pg_stat_replication WHERE state = '"'"'streaming'"'"'")" == 1 ]]; do sleep 1; done'

for name in octavius-mh-primary octavius-mh-standby octavius-mh-foreign; do
    echo "$name: in recovery $(docker exec $name psql -U postgres -tAc 'SELECT pg_is_in_recovery()')," \
        "system identifier $(docker exec $name psql -U postgres -tAc 'SELECT system_identifier FROM pg_control_system()')"
done
