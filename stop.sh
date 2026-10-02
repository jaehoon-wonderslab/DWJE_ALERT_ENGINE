#!/usr/bin/env bash
# =====================================================================================
#  이상 알림 발송 엔진 — 중지 스크립트 (안전 종료)
#
#  종료 신호(SIGTERM)를 보내고, 진행 중인 틱이 끝날 때까지 기다린다.
#  프로세스를 즉시 죽이지 않는다 — 발송 도중에 끊으면 그 건이 SENDING 으로 남고,
#  다음 기동에서 회수될 때까지(기본 5분) 발송이 미뤄진다.
#
#  엔진 쪽 동작 (AlertEngineScheduler.awaitRunningTick)
#    · 새 트리거를 더 받지 않는다
#    · 진행 중인 틱은 끝까지 수행한다 (최대 120초 대기)
#    · 다 끝나면 커넥션 풀을 닫고 스스로 종료한다
#
#  사용법
#    ./stop.sh                   안전 종료 (최대 180초 대기)
#    ./stop.sh --timeout=300     더 길게 기다린다
#    ./stop.sh --force           대기 시간이 지나면 강제 종료(SIGKILL)까지 진행
#    ./stop.sh --pid=12345       PID 파일 없이 뜬 프로세스를 직접 지정
# =====================================================================================
if [ -z "${BASH_VERSION:-}" ]; then
    if command -v bash >/dev/null 2>&1; then
        exec bash "$0" "$@"
    fi
    echo "[오류] 이 스크립트는 bash 가 필요합니다. 설치하십시오: sudo apt install -y bash" >&2
    exit 1
fi

set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_NAME="alert-engine.jar"
PID_FILE="${PID_FILE:-$APP_HOME/run/alert-engine.pid}"
LOG_DIR="${LOG_DIR:-$APP_HOME/logs}"

# 엔진이 최악의 경우 쓰는 시간 = shutdown-wait-sec(120) + 스레드 풀 2차 대기(30) + 뒷정리.
# 150 으로 두면 딱 그 경계라 아슬아슬하게 놓친다. 여유를 둔다.
TIMEOUT="${STOP_TIMEOUT:-180}"
FORCE=0
PID=""

log()  { printf '\033[1;34m[중지]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[주의]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[오류]\033[0m %s\n' "$*" >&2; exit 1; }

cmdline_of() {
    if [ -r "/proc/$1/cmdline" ]; then
        tr '\0' ' ' < "/proc/$1/cmdline"
    else
        ps -ww -p "$1" -o command= 2>/dev/null
    fi
}

find_engine_pids() {
    if command -v pgrep >/dev/null 2>&1; then
        pgrep -f "java .*$JAR_NAME" 2>/dev/null || true
    else
        ps -ww -eo pid=,command= 2>/dev/null \
            | grep -E "java .*$JAR_NAME" | grep -v grep | awk '{print $1}' || true
    fi
}

is_engine() { cmdline_of "$1" | grep -q "$JAR_NAME"; }

is_zombie() {
    if [ -r "/proc/$1/stat" ]; then
        [ "$(sed -e 's/^.*) //' -e 's/ .*//' "/proc/$1/stat" 2>/dev/null)" = "Z" ]
    else
        [ "$(ps -p "$1" -o state= 2>/dev/null | tr -d ' ' | cut -c1)" = "Z" ]
    fi
}

is_alive() { kill -0 "$1" 2>/dev/null && ! is_zombie "$1"; }

for arg in "$@"; do
    case "$arg" in
        --force)      FORCE=1 ;;
        --timeout=*)  TIMEOUT="${arg#*=}" ;;
        --pid=*)      PID="${arg#*=}" ;;
        -h|--help)    sed -n '2,/^# =\{10,\}$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            die "알 수 없는 옵션: $arg  (--force / --timeout=초 / --pid=PID)" ;;
    esac
done

[[ "$TIMEOUT" =~ ^[0-9]+$ ]] || die "--timeout 은 초 단위 숫자여야 합니다: $TIMEOUT"

# ── 1. 대상 PID 확인 ─────────────────────────────────────────────────────────────────
if [[ -z "$PID" && -f "$PID_FILE" ]]; then
    PID="$(cat "$PID_FILE" 2>/dev/null || true)"
fi

if [[ -z "$PID" ]]; then
    FOUND="$(find_engine_pids)"
    if [[ -z "$FOUND" ]]; then
        log "실행 중인 엔진이 없습니다."
        rm -f "$PID_FILE"
        exit 0
    fi
    [[ "$(echo "$FOUND" | wc -l)" -eq 1 ]] \
        || die "엔진 프로세스가 여러 개입니다 (PID: $(echo "$FOUND" | tr '\n' ' ')).
     --pid=<PID> 로 하나씩 지정해 중지하십시오."
    PID="$(echo "$FOUND" | tr -d ' ')"
    warn "PID 파일이 없어 프로세스를 직접 찾았습니다 (PID $PID)."
fi

if ! kill -0 "$PID" 2>/dev/null; then
    log "PID $PID 는 실행 중이 아닙니다. PID 파일을 정리합니다."
    rm -f "$PID_FILE"
    exit 0
fi

if is_zombie "$PID"; then
    log "PID $PID 는 이미 종료했습니다 (좀비 — 부모가 거두지 않은 상태)."
    rm -f "$PID_FILE"
    exit 0
fi

# 엉뚱한 프로세스에 신호를 보내지 않는다. PID 는 재사용된다.
if ! is_engine "$PID"; then
    rm -f "$PID_FILE"
    die "PID $PID 는 알림 엔진이 아닙니다. 신호를 보내지 않았습니다.
     (남아 있던 PID 파일은 정리했습니다)"
fi

log "PID $PID 에 종료 신호(SIGTERM)를 보냅니다."
kill -TERM "$PID" 2>/dev/null || die "종료 신호를 보내지 못했습니다 (권한을 확인하십시오)."

# ── 2. 종료 대기 ─────────────────────────────────────────────────────────────────────
START_TS=$(date +%s)
while is_alive "$PID"; do
    ELAPSED=$(( $(date +%s) - START_TS ))
    if [[ "$ELAPSED" -ge "$TIMEOUT" ]]; then
        printf '\n'
        if [[ "$FORCE" -eq 1 ]]; then
            warn "${TIMEOUT}초를 기다렸습니다. --force 지정에 따라 강제 종료(SIGKILL)합니다."
            warn "발송 중이던 건은 다음 기동에서 회수됩니다 (알림 자체는 남아 있습니다)."
            kill -KILL "$PID" 2>/dev/null || true
            sleep 1
            break
        fi
        warn "${TIMEOUT}초 안에 종료되지 않았습니다. 프로세스를 그대로 두었습니다 (PID $PID)."
        echo
        echo "  진행 상황 확인   tail -f $LOG_DIR/alert.log"
        echo "  더 기다리기      $APP_HOME/stop.sh --timeout=600"
        echo "  강제 종료        $APP_HOME/stop.sh --force"
        exit 1
    fi
    printf '\r\033[1;34m[중지]\033[0m 종료 대기 중 — %d초 경과 (최대 %d초)   ' "$ELAPSED" "$TIMEOUT"
    sleep 1
done

printf '\r\033[K'
rm -f "$PID_FILE"
log "엔진이 종료되었습니다 (PID $PID, 소요 $(( $(date +%s) - START_TS ))초)."

if [[ -f "$LOG_DIR/alert.log" ]]; then
    echo
    tail -n 5 "$LOG_DIR/alert.log"
fi
