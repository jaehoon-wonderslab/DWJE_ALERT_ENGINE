package com.dwje.alert.cli

/**
 * 사용법 안내문.
 *
 * Spring 컨텍스트 **밖에서도** 쓸 수 있어야 한다. 접속 설정 검증이 `@PostConstruct` 에서
 * 기동을 거부하므로, 설정 없이 `--help` 를 부르면 컨텍스트가 뜨기 전에 죽는다.
 * 옵션을 알아보려는 사람이 도움말 대신 스택 트레이스를 보지 않도록,
 * `main()` 이 컨텍스트를 띄우기 전에 이 문구를 직접 출력한다.
 */
object Usage {

    /** `--help` / `-h` 인지 */
    fun isHelpRequest(args: Array<String>): Boolean =
        args.any { it == "--help" || it == "-h" || it.startsWith("--help=") }

    fun text(
        scheduleDesc: String = "1분마다",
        timezone: String = "Asia/Seoul",
    ): String = """
        |
        |이상 알림 발송 엔진 — 조건 판정 · 알림 발생 · 발송 (SY-04)
        |
        |사용법
        |  java -jar alert-engine.jar --spring.profiles.active=<local|prod> [옵션]
        |
        |프로파일 (기본값 없음 — 실행하는 쪽이 밝힌다)
        |  local                   개발 PC. 메일을 보내지 않고 내용을 로그로 남긴다(LOG 모드)
        |  prod                    운영 서버. 실제 메일 발송(SMTP), INFO 로깅
        |
        |실행 모드 (상호 배타)
        |  (없음)                  상주 모드. $scheduleDesc 판정 ($timezone)
        |  --interval=1m           상주 모드. 벽시계 기준 1분마다 (단위: s 초 · m 분(기본) · h 시간)
        |                          60(초·분)·24(시간)를 나누어떨어지는 값만 — 경계에 정렬하기 위함
        |  --cron="0 * * * * *"    상주 모드. Spring cron 6필드(초 분 시 일 월 요일) 직접 지정
        |  --now                   즉시 1회 실행 후 종료
        |
        |대상 한정
        |  --cond=12,15            조건 번호만 판정 (ax.tb_alm_cond.cond_id)
        |
        |기타
        |  --dry-run               판정만 한다. 알림·발송은 물론 판정 상태도 쓰지 않는다
        |  --user=<id>             실행 이력에 남길 실행자 (기본 SYSTEM)
        |  --timezone=<zone>       스케줄 기준 타임존 (기본 $timezone)
        |  --help                  이 도움말 (접속 설정 없이도 볼 수 있다)
        |
        |한 번의 실행에서 하는 일
        |  ① 수집  원천(mes·ax) → ax.tb_met_metric_value
        |  ② 평가  조건 × 대상 비교 + 지속 조건 → ax.tb_alm_cond_state
        |  ③ 발생  유효 시간대·중복 억제 → ax.tb_alm_alert + ax.tb_alm_send_queue
        |  ④ 발송  대기열 → 메일·팝업 → ax.tb_alm_send_log
        |
        |접속 설정
        |  환경변수 3개가 필요하다. 없으면 기동을 거부하고 설정 방법을 안내한다.
        |    ALERT_DB_URL / ALERT_DB_USERNAME / ALERT_DB_PASSWORD
        |  계정 변수명은 USERNAME 이다 (..._USER 는 바인딩되지 않는다).
        |
        |사전 조건
        |  API 프로젝트의 db/V35__alm_engine.sql 이 적용돼 있어야 한다.
        |  없으면 기동은 하되 무엇이 없는지 알리고 아무 일도 하지 않는다.
        |
        |종료 코드
        |  0 정상  |  1 실행 실패  |  2 옵션·기동 오류
        |
    """.trimMargin()
}
