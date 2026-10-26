#!/usr/bin/env bash
# Test load on the local Oracle test database (Docker container), for checking the app with known activity.
#
#   tools/testdb/testdb.sh check                 container, port and listener
#   tools/testdb/testdb.sh start                 start the container (and fix a lost network) and wait for the PDB
#   tools/testdb/testdb.sh setup                 create the test tables (once; again after cleanup)
#   tools/testdb/testdb.sh run <scenario> [minutes] [sessions]   start a load in the background
#   tools/testdb/testdb.sh status                running loads (sessions with MODULE ashv_test)
#   tools/testdb/testdb.sh stop                  ask the running loads to stop
#   tools/testdb/testdb.sh cleanup               stop and drop the test tables
#   tools/testdb/testdb.sh sql "<statement>"     run one statement as the test user
#
# Scenarios: cpu | io | plan_change | long_exec | many_execs | lock | mixed
# All sessions set MODULE = 'ashv_test' and ACTION = <scenario>: filter by them in the app.
#
# Settings (environment or ~/.config/mp-ashv/testdb.env): ASHV_TESTDB_CONTAINER (oracle19c), ASHV_TESTDB_USER
# (user_teste), ASHV_TESTDB_PASSWORD (required), ASHV_TESTDB_SERVICE (orclpdb1), ASHV_TESTDB_PORT (1521).
set -euo pipefail

[ -f "$HOME/.config/mp-ashv/testdb.env" ] && . "$HOME/.config/mp-ashv/testdb.env"
CONTAINER="${ASHV_TESTDB_CONTAINER:-oracle19c}"
DB_USER="${ASHV_TESTDB_USER:-user_teste}"
SERVICE="${ASHV_TESTDB_SERVICE:-orclpdb1}"
PORT="${ASHV_TESTDB_PORT:-1521}"
HERE="$(cd "$(dirname "$0")" && pwd)"
LOGS="${TMPDIR:-/tmp}/ashv_testdb"
REMOTE_DIR=/tmp/ashv_testdb
mkdir -p "$LOGS"

die() { echo "ERRO: $*" >&2; exit 1; }

password() {
    [ -n "${ASHV_TESTDB_PASSWORD:-}" ] || die "defina ASHV_TESTDB_PASSWORD (ou crie ~/.config/mp-ashv/testdb.env)"
    echo "$ASHV_TESTDB_PASSWORD"
}

running() { [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" = "true" ]; }

# The port must be published by the test container: production databases are reached through tunnels on localhost
check_port() {
    running || die "container $CONTAINER parado (use: $0 start)"
    docker port "$CONTAINER" 1521/tcp 2>/dev/null | grep -q ":$PORT\$" \
        || die "a porta $PORT nao e publicada pelo container $CONTAINER: nao conecto (pode ser um tunel para producao)"
}

service_up() {
    docker exec "$CONTAINER" lsnrctl status 2>/dev/null | grep -qi "service \"$SERVICE\""
}

cmd_check() {
    running && echo "container $CONTAINER: rodando" || { echo "container $CONTAINER: parado"; return 1; }
    check_port && echo "porta $PORT: publicada pelo container"
    service_up && echo "listener: servico $SERVICE registrado" || { echo "listener: servico $SERVICE NAO registrado"; return 1; }
}

cmd_start() {
    running || docker start "$CONTAINER" >/dev/null
    # seen on 2026-10-25: the container lost its network (no IP, no published ports)
    if [ "$(docker inspect -f '{{json .NetworkSettings.Networks}}' "$CONTAINER")" = "{}" ]; then
        echo "container sem rede: religando a bridge"
        docker network connect bridge "$CONTAINER"
        docker restart "$CONTAINER" >/dev/null
    fi
    check_port
    for i in $(seq 1 36); do
        service_up && { echo "pronto: $SERVICE no ar"; return 0; }
        # instance up but not registered in the listener
        [ "$i" = 12 ] && echo "alter system register;" | docker exec -i "$CONTAINER" sqlplus -s / as sysdba >/dev/null || true
        sleep 5
    done
    die "servico $SERVICE nao apareceu no listener em 3 minutos"
}

copy_sql() {
    docker exec "$CONTAINER" mkdir -p "$REMOTE_DIR"
    docker cp "$HERE/sql/." "$CONTAINER:$REMOTE_DIR/" >/dev/null
}

# sqlplus runs inside the container (not installed on the host)
sqlplus_script() {
    local script="$1"; shift
    docker exec -i "$CONTAINER" sqlplus -s -L "$DB_USER/$(password)@localhost:1521/$SERVICE" "@$REMOTE_DIR/$script" "$@"
}

cmd_sql() {
    check_port
    printf 'whenever sqlerror exit failure\nset pagesize 200 linesize 200 feedback on\n%s\nexit\n' "$1" \
        | docker exec -i "$CONTAINER" sqlplus -s -L "$DB_USER/$(password)@localhost:1521/$SERVICE"
}

db_time() { cmd_sql "select to_char(sysdate, 'YYYY-MM-DD HH24:MI:SS') agora from dual;" | grep -E '^[0-9]{4}-' ; }

launch() {   # <log name> <script> args...: background session, survives the end of this script
    local name="$1" script="$2"; shift 2
    nohup docker exec -i "$CONTAINER" sqlplus -s -L "$DB_USER/$(password)@localhost:1521/$SERVICE" \
        "@$REMOTE_DIR/$script" "$@" < /dev/null > "$LOGS/$name.log" 2>&1 &
}

cmd_run() {
    local scenario="${1:-}" minutes="${2:-5}" sessions="${3:-2}"
    [ -n "$scenario" ] || die "informe o cenario"
    check_port
    copy_sql
    cmd_sql "update ashv_ctl set stop = 0;
commit;" >/dev/null || die "rode antes: $0 setup"
    local start; start="$(db_time)"
    case "$scenario" in
        cpu|io|long_exec|many_execs)
            for s in $(seq 1 "$sessions"); do launch "${scenario}_$s" "$scenario.sql" "$minutes"; done ;;
        plan_change)
            launch plan_change plan_change.sql "$minutes" ;;
        lock)
            launch lock_holder lock_holder.sql "$minutes"
            sleep 3
            for s in $(seq 1 "$sessions"); do launch "lock_waiter_$s" lock_waiter.sql; done ;;
        mixed)
            launch mixed_cpu cpu.sql "$minutes"
            launch mixed_io io.sql "$minutes"
            launch mixed_long long_exec.sql "$minutes"
            launch mixed_short many_execs.sql "$minutes" ;;
        *) die "cenario desconhecido: $scenario" ;;
    esac
    case "$scenario" in plan_change) sessions=1 ;; mixed) sessions=4 ;; esac
    echo "cenario: $scenario | sessoes: $sessions | inicio (hora do banco): $start | duracao: ${minutes} min"
    [ "$scenario" = plan_change ] && echo "troca de plano na metade (indice fica visivel): veja $LOGS/plan_change.log"
    echo "logs: $LOGS | filtro no app: MODULE = ashv_test, ACTION = $scenario"
}

cmd_status() {
    cmd_sql "select sid, serial#, action, status, event, sql_id from v\$session where module = 'ashv_test' order by action, sid;"
}

cmd_stop() {
    cmd_sql "update ashv_ctl set stop = 1;
commit;" >/dev/null && echo "pedido de parada enviado (as sessoes terminam em poucos segundos)"
}

cmd_setup() { check_port; copy_sql; sqlplus_script setup.sql; }

cmd_cleanup() {
    check_port; copy_sql
    cmd_sql "update ashv_ctl set stop = 1;
commit;" >/dev/null 2>&1 || true
    sleep 6
    sqlplus_script cleanup.sql
}

case "${1:-}" in
    check) cmd_check ;;
    start) cmd_start ;;
    setup) cmd_setup ;;
    run) shift; cmd_run "$@" ;;
    status) cmd_status ;;
    stop) cmd_stop ;;
    cleanup) cmd_cleanup ;;
    sql) shift; cmd_sql "$*" ;;
    *) sed -n '2,20p' "$0"; exit 1 ;;
esac
