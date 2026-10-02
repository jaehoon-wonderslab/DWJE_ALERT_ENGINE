#!/usr/bin/env bash
# =====================================================================================
#  이상 알림 발송 엔진 — 상태 확인
#
#  프로세스가 떠 있는지, 그리고 **실제로 판정을 돌고 있는지** 를 함께 본다.
#  둘은 다른 이야기다 — 프로세스는 살아 있는데 DB 접속이 끊겨 아무 일도 못 하는 상태가
#  가장 알아채기 어렵다. 그래서 DB 의 실행 이력(ax.tb_alm_eval_run)까지 본다.
#
#  사용법
#    ./status.sh                 프로세스 + 최근 실행 이력
#    ./status.sh --no-db         프로세스만 (psql 이 없거나 DB 가 멀 때)
# =====================================================================================
if [ -z "${BASH_VERSION:-}" ]; then
    command -v bash >/dev/null 2>&1 && exec bash "$0" "$@"
    echo "[오류] bash 가 필요합니다." >&2; exit 1
fi
set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_NAME="alert-engine.jar"
PID_FILE="${PID_FILE:-$APP_HOME/run/alert-engine.pid}"
LOG_DIR="${LOG_DIR:-$APP_HOME/logs}"
NO_DB=0

for arg in "$@"; do
    case "$arg" in
        --no-db)   NO_DB=1 ;;
        -h|--help) sed -n '2,/^# =\{10,\}$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)         echo "알 수 없는 옵션: $arg" >&2; exit 1 ;;
    esac
done

ok()   { printf '\033[1;32m  ●\033[0m %s\n' "$*"; }
bad()  { printf '\033[1;31m  ●\033[0m %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }

echo
echo "이상 알림 발송 엔진 상태"
echo "────────────────────────────────────────────"

# ── 1. 프로세스 ──────────────────────────────────────────────────────────────────────
PID=""
[[ -f "$PID_FILE" ]] && PID="$(cat "$PID_FILE" 2>/dev/null || true)"
if [[ -z "$PID" ]]; then
    if command -v pgrep >/dev/null 2>&1; then
        PID="$(pgrep -f "java .*$JAR_NAME" 2>/dev/null | head -1 || true)"
    fi
fi

if [[ -n "$PID" ]] && kill -0 "$PID" 2>/dev/null; then
    ok "프로세스 실행 중 (PID $PID)"
    if command -v ps >/dev/null 2>&1; then
        info "$(ps -p "$PID" -o etime=,rss= 2>/dev/null | awk '{printf "가동 %s · 메모리 %.0fMB", $1, $2/1024}')"
    fi
else
    bad "프로세스가 실행 중이 아닙니다"
    info "기동: $APP_HOME/start.sh"
fi

# ── 2. 로그 ──────────────────────────────────────────────────────────────────────────
if [[ -f "$LOG_DIR/alert.log" ]]; then
    echo
    echo "최근 로그 5줄 ($LOG_DIR/alert.log)"
    tail -n 5 "$LOG_DIR/alert.log" | sed 's/^/    /'
fi

# ── 3. DB 의 실행 이력 ───────────────────────────────────────────────────────────────
# 프로세스가 살아 있어도 판정을 못 하고 있을 수 있다. 그것은 DB 에서만 보인다.
if [[ "$NO_DB" -eq 0 ]] && command -v psql >/dev/null 2>&1 && [[ -n "${ALERT_DB_URL:-}" ]]; then
    echo
    echo "최근 실행 이력 (ax.tb_alm_eval_run)"
    PGURL="$(echo "$ALERT_DB_URL" | sed 's|^jdbc:||')"
    PGPASSWORD="${ALERT_DB_PASSWORD:-}" psql "$PGURL" -U "${ALERT_DB_USERNAME:-}" -A -F ' | ' -t -c \
        "SELECT to_char(started_at,'MM-DD HH24:MI:SS'), state_cd, eval_cnt, raise_cnt, sent_cnt, coalesce(message,'')
           FROM ax.tb_alm_eval_run ORDER BY started_at DESC LIMIT 5" 2>/dev/null | sed 's/^/    /' \
        || info "(DB 조회에 실패했습니다 — 접속 정보를 확인하십시오)"
else
    echo
    info "DB 이력은 psql 과 ALERT_DB_URL 이 있을 때 함께 보여 줍니다 (--no-db 로 생략)."
fi
echo
