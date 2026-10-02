#!/usr/bin/env bash
# =====================================================================================
#  이상 알림 발송 엔진 — 기동 스크립트
#
#  사용법 — 상주 기동 (백그라운드, PID 파일을 남긴다)
#    ./start.sh                      상주 모드로 기동 (기본 prod 프로파일, 1분 주기)
#    ./start.sh --profile=local      로컬 프로파일로 기동 (메일을 보내지 않고 로그로만)
#    ./start.sh --interval=30s       엔진 옵션을 그대로 전달
#
#  사용법 — 1회 실행 (앞단에서 돌고 결과를 그 자리에 뿌린다. PID 파일을 남기지 않는다)
#    ./start.sh --now --dry-run      판정만 확인. 알림·발송·상태 변경 없음
#    ./start.sh --now --user=<사번>   즉시 1회 판정·발송
#    ./start.sh --now --cond=12      조건 12번만 판정
#    ./start.sh --help               옵션 전체
#
#  --profile 을 제외한 모든 인자는 엔진에 그대로 넘어간다.
#  이 스크립트는 JAR 과 같은 폴더에 두거나, 프로젝트 루트에 두고 쓴다. 두 경우 모두 JAR 을 찾는다.
# =====================================================================================
# ── dash 로 실행돼도 bash 로 다시 띄운다 ─────────────────────────────────────────────
#  우분투의 /bin/sh 는 dash 다. `sh start.sh` 로 부르면 아래 `set -o pipefail` 에서
#  "Illegal option -o pipefail" 로 멈춘다. 배열·[[ ]] 를 쓰므로 bash 로 올려 잡는다.
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
CONSOLE_LOG="$LOG_DIR/console.out"

# 프로파일 기본값은 prod 다. 엔진 자체는 기본값을 두지 않는다 — 운영 서버에서 프로파일을
# 빠뜨리면 메일 모드가 LOG 로 떨어져 아무도 알림을 못 받는다. 스크립트가 대신 밝혀 준다.
PROFILE="${ALERT_PROFILE:-prod}"

# 판정은 질의 몇 개가 전부라 힙이 크지 않다.
# stdout.encoding 을 빼면 안 된다. 우분투 서버는 LANG 이 비어 있는 경우가 흔한데,
# 그러면 Java 가 콘솔 인코딩을 ASCII 로 잡아 한글 로그가 전부 '?' 로 나온다.
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx1g -XX:+UseG1GC -Duser.timezone=Asia/Seoul -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8}"

log()  { printf '\033[1;34m[기동]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[주의]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[오류]\033[0m %s\n' "$*" >&2; exit 1; }

# ── 프로세스 조회 도우미 ─────────────────────────────────────────────────────────────
#  ps 는 COLUMNS 가 걸려 있으면 그 폭에서 명령줄을 잘라 버린다. 경로가 긴 서버에서는
#  그 지점에서 JAR 이름이 날아가 "이 PID 는 엔진이 아니다" 로 오판한다.
#  리눅스에서는 /proc 를 먼저 읽고(항상 정확하다), 없으면 -ww 로 폭 제한을 푼다.
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

#  좀비 — 이미 종료했지만 부모가 거두지 않아 PID 만 남은 상태. `kill -0` 은 좀비에도 성공한다.
is_zombie() {
    if [ -r "/proc/$1/stat" ]; then
        [ "$(sed -e 's/^.*) //' -e 's/ .*//' "/proc/$1/stat" 2>/dev/null)" = "Z" ]
    else
        [ "$(ps -p "$1" -o state= 2>/dev/null | tr -d ' ' | cut -c1)" = "Z" ]
    fi
}

is_alive() { kill -0 "$1" 2>/dev/null && ! is_zombie "$1"; }

# ── 인자 분리 — --profile 만 스크립트가 먹고 나머지는 엔진으로 넘긴다 ──────────────────
ENGINE_ARGS=()
for arg in "$@"; do
    case "$arg" in
        --profile=*)                 PROFILE="${arg#*=}" ;;
        --spring.profiles.active=*)  PROFILE="${arg#*=}" ;;
        *)                           ENGINE_ARGS+=("$arg") ;;
    esac
done

# 1회 실행 옵션이 섞여 있으면 상주 기동이 아니다. 엔진이 일을 마치고 스스로 종료하므로
# 백그라운드로 돌리면 "기동 직후 종료" 로 잘못 보고된다. 앞단에서 그대로 돌린다.
ONESHOT=0
HELP=0
for arg in ${ENGINE_ARGS[@]+"${ENGINE_ARGS[@]}"}; do
    case "$arg" in
        --help|-h) HELP=1; ONESHOT=1 ;;
        --now)     ONESHOT=1 ;;
    esac
done

# ── 1. JAR 찾기 ──────────────────────────────────────────────────────────────────────
if   [[ -f "$APP_HOME/$JAR_NAME" ]];            then JAR="$APP_HOME/$JAR_NAME"
elif [[ -f "$APP_HOME/build/libs/$JAR_NAME" ]]; then JAR="$APP_HOME/build/libs/$JAR_NAME"
else
    die "JAR 을 찾을 수 없습니다: $APP_HOME/$JAR_NAME 또는 $APP_HOME/build/libs/$JAR_NAME
     먼저 ./gradlew clean dist 를 실행하십시오."
fi

# 엔진은 실행 위치 기준으로 ./config/local.yml 을 읽는다. JAR 이 있는 폴더에서 띄워야
# 그 옆의 설정이 잡힌다.
WORK_DIR="$(cd "$(dirname "$JAR")" && pwd)"

# ── 2. Java 확인 ─────────────────────────────────────────────────────────────────────
command -v java >/dev/null \
    || die "java 를 찾을 수 없습니다. 설치하십시오: sudo apt install -y openjdk-21-jre-headless"

JAVA_MAJOR=$(java -version 2>&1 | grep -i 'version' | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ && "$JAVA_MAJOR" -ge 21 ]] \
    || die "JDK 21 이상이 필요합니다 (현재 ${JAVA_MAJOR:-알 수 없음}).
     sudo apt install -y openjdk-21-jre-headless"

# 도움말은 접속 설정이 없어도 볼 수 있어야 한다. 중복 검사·env 적재를 건너뛴다.
# shellcheck disable=SC2086
[[ "$HELP" -eq 0 ]] || exec java $JAVA_OPTS -jar "$JAR" --help

# ── 3. 중복 기동 차단 ────────────────────────────────────────────────────────────────
# advisory lock 은 판정이 겹치는 것만 막는다. 프로세스가 둘 뜨는 것은 여기서 막는다.
if [[ "$ONESHOT" -eq 1 ]]; then
    RUNNING="$(find_engine_pids)"
    [[ -z "$RUNNING" ]] || warn "상주 엔진이 실행 중입니다 (PID: $(echo "$RUNNING" | tr '\n' ' ')).
     판정이 겹치면 advisory lock 에 막혀 이번 요청은 수행되지 않을 수 있습니다."
elif [[ -f "$PID_FILE" ]]; then
    OLD_PID="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [[ -n "$OLD_PID" ]] && kill -0 "$OLD_PID" 2>/dev/null; then
        if is_engine "$OLD_PID"; then
            die "엔진이 이미 실행 중입니다 (PID $OLD_PID). 먼저 ./stop.sh 로 중지하십시오."
        fi
        warn "PID $OLD_PID 는 엔진이 아닙니다. PID 파일을 무시하고 진행합니다."
    else
        warn "남아 있던 PID 파일을 정리합니다 (PID ${OLD_PID:-없음} 는 실행 중이 아닙니다)."
    fi
    rm -f "$PID_FILE"
fi

if [[ "$ONESHOT" -eq 0 ]]; then
    STRAY="$(find_engine_pids)"
    [[ -z "$STRAY" ]] || die "PID 파일 없이 실행 중인 엔진이 있습니다 (PID: $(echo "$STRAY" | tr '\n' ' ')).
     확인 후 정리하십시오: ./stop.sh --pid=<PID>"
fi

# ── 4. 접속 정보 ─────────────────────────────────────────────────────────────────────
# 엔진이 직접 읽는 것은 config/local.yml 뿐이다. engine.env 는 셸이 source 해야 한다.
ENV_FILE="${ENV_FILE:-}"
if [[ -z "$ENV_FILE" ]]; then
    for candidate in "$WORK_DIR/config/engine.env" "$APP_HOME/config/engine.env" "/etc/default/alert-engine"; do
        [[ -f "$candidate" ]] && { ENV_FILE="$candidate"; break; }
    done
fi
if [[ -n "$ENV_FILE" ]]; then
    [[ -f "$ENV_FILE" ]] || die "지정한 환경변수 파일이 없습니다: $ENV_FILE"
    # shellcheck disable=SC1090
    set -a; . "$ENV_FILE"; set +a
    log "접속 정보 — $ENV_FILE"
elif [[ -f "$WORK_DIR/config/local.yml" ]]; then
    log "접속 정보 — $WORK_DIR/config/local.yml (엔진이 직접 읽습니다)"
elif [[ -z "${ALERT_DB_URL:-}" ]]; then
    warn "접속 설정을 찾지 못했습니다. 엔진이 기동을 거부할 수 있습니다.
     config/engine.env 또는 config/local.yml 을 JAR 옆에 두거나 ENV_FILE 로 지정하십시오."
fi

# ── 5. 기동 ──────────────────────────────────────────────────────────────────────────
mkdir -p "$LOG_DIR" "$(dirname "$PID_FILE")"

[[ "$ONESHOT" -eq 0 ]] || log "1회 실행 — 끝나면 종료됩니다 (PID 파일을 남기지 않습니다)"
log "JAR      $JAR"
log "프로파일 $PROFILE"
log "로그     $LOG_DIR/alert.log"
[[ ${#ENGINE_ARGS[@]} -eq 0 ]] || log "옵션     ${ENGINE_ARGS[*]}"

cd "$WORK_DIR"

# 기동 확인은 "이번 실행이 새로 쓴 로그" 만 봐야 한다. 파일 전체를 grep 하면 어제 남은
# '스케줄 등록' 에 걸려, 설정 오류로 죽은 기동도 성공으로 보고한다.
LOG_OFFSET=0
if [[ -f "$LOG_DIR/alert.log" ]]; then
    LOG_OFFSET=$(wc -c < "$LOG_DIR/alert.log" 2>/dev/null | tr -d ' ')
    [[ "$LOG_OFFSET" =~ ^[0-9]+$ ]] || LOG_OFFSET=0
fi

# 1회 실행 — 앞단에서 돌리고 엔진의 종료 코드를 그대로 넘긴다.
if [[ "$ONESHOT" -eq 1 ]]; then
    echo
    export LOG_DIR
    # shellcheck disable=SC2086
    exec java $JAVA_OPTS -jar "$JAR" \
        "--spring.profiles.active=$PROFILE" \
        ${ENGINE_ARGS[@]+"${ENGINE_ARGS[@]}"}
fi

# 상주 기동 — 백그라운드로 띄우고 PID 를 남긴다
# shellcheck disable=SC2086
LOG_DIR="$LOG_DIR" nohup java $JAVA_OPTS -jar "$JAR" \
    "--spring.profiles.active=$PROFILE" \
    ${ENGINE_ARGS[@]+"${ENGINE_ARGS[@]}"} \
    >> "$CONSOLE_LOG" 2>&1 &

PID=$!
echo "$PID" > "$PID_FILE"

# ── 6. 기동 확인 ─────────────────────────────────────────────────────────────────────
new_log() { tail -c "+$((LOG_OFFSET + 1))" "$LOG_DIR/alert.log" 2>/dev/null || true; }

READY=0
for _ in $(seq 1 40); do
    is_alive "$PID" || break
    if new_log | grep -q "스케줄 등록"; then
        READY=1
        break
    fi
    sleep 0.5
done

if ! is_alive "$PID"; then
    rm -f "$PID_FILE"
    printf '\n'
    warn "엔진이 기동 직후 종료되었습니다. 마지막 로그입니다."
    tail -n 25 "$CONSOLE_LOG" 2>/dev/null || true
    exit 1
fi

if [[ "$READY" -eq 0 ]]; then
    warn "프로세스는 떴지만(PID $PID) 20초 안에 스케줄 등록을 확인하지 못했습니다.
     아직 기동 중일 수 있습니다. 로그를 확인하십시오: tail -f $LOG_DIR/alert.log"
else
    log "기동 완료 — PID $PID"
fi
echo
echo "  로그 보기   tail -f $LOG_DIR/alert.log"
echo "  상태 확인   $APP_HOME/status.sh"
echo "  중지       $APP_HOME/stop.sh"
