package com.dwje.alert.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.LocalTime

/**
 * 엔진 동작 설정. application.yml 의 `alert.*` 에 대응한다.
 * CLI 옵션이 주어지면 [com.dwje.alert.cli.CliOptions] 가 스케줄 값을 덮어쓴다.
 */
@ConfigurationProperties(prefix = "alert")
data class AlertProperties(
    val schedule: Schedule = Schedule(),
    val db: Db = Db(),
    val engine: Engine = Engine(),
    val collect: Collect = Collect(),
    val dispatch: Dispatch = Dispatch(),
    val escalation: Escalation = Escalation(),
    val night: Night = Night(),
    val message: Message = Message(),
) {
    data class Schedule(
        /**
         * 기본 반복 주기 — 1분마다.
         *
         * 벽시계 경계에 맞춘다. 13:02:30 에 띄우면 첫 실행이 13:03:00 이다.
         * 기동 시각부터 세지 않으므로 재기동해도 판정 시각이 밀리지 않는다.
         *
         * '10분 연속' 같은 지속 조건을 분 단위로 보므로 1분보다 성기게 두면
         * 연속 판정의 해상도가 그만큼 떨어진다.
         */
        val interval: String? = "1m",
        /** 지정 시 interval 보다 우선하는 Spring cron 표현식 (6필드) */
        val cron: String? = null,
        val timezone: String = "Asia/Seoul",
    )

    /**
     * 대상 PostgreSQL 접속.
     *
     * **url · username · password 는 코드에 두지 않는다.** 환경변수로 주입한다.
     *   ALERT_DB_URL / ALERT_DB_USERNAME / ALERT_DB_PASSWORD
     *
     * 값이 비어 있으면 기동 시 [AlertConfigValidator] 가 막는다. 기본값을 두면
     * 설정을 빠뜨린 채 엉뚱한 서버(localhost 등)에 붙어 조용히 실패하기 때문이다.
     */
    data class Db(
        val url: String = "",
        val username: String = "",
        val password: String = "",
        val maxPoolSize: Int = 4,
    )

    data class Engine(
        /**
         * 다중 인스턴스 동시 실행을 막는 advisory lock 키.
         * 이관 엔진(8,260,829)과 겹치지 않는 값을 쓴다 — 겹치면 서로를 막는다.
         */
        val advisoryLockKey: Long = 480_401L,
        /** 한 틱에서 판정할 (조건 × 대상) 상한. 남은 것은 다음 틱에서 본다 */
        val maxEvalPerTick: Int = 2_000,
        /**
         * 지표 값이 `수집주기 × 이 배수` 보다 오래되면 그 조건을 판정하지 않는다.
         *
         * 멈춘 수집의 낡은 값으로 판정하면 이미 끝난 이상이 계속 나가거나
         * 진짜 이상을 정상으로 본다. 둘 다 알림을 못 믿게 만든다.
         */
        val staleFactor: Int = 3,
        /**
         * 아무 일도 없던 틱은 실행 이력을 남기지 않는다. 1분 주기면 하루 1,440행이
         * 쌓여 정작 봐야 할 실행이 묻힌다. 조용한 구간은 이 주기(분)로 요약 1행만 남긴다.
         */
        val heartbeatMin: Long = 60,
        /**
         * 종료 신호를 받았을 때 진행 중인 틱이 끝나기를 기다리는 최대 시간(초).
         * stop.sh 의 STOP_TIMEOUT(기본 180초)은 이 값보다 커야 한다.
         */
        val shutdownWaitSec: Long = 120,
    )

    data class Collect(
        val enabled: Boolean = true,
        /** 수집 질의 타임아웃(초). 원천이 느릴 때 틱 전체가 밀리는 것을 막는다 */
        val statementTimeoutSec: Int = 30,
        /** 한 번의 수집에서 적재할 지표 값 상한 */
        val maxPointsPerRun: Int = 5_000,
    )

    data class Dispatch(
        val enabled: Boolean = true,
        val batchSize: Int = 50,
        val maxTry: Int = 5,
        /** 재시도 간격(초). 시도 횟수만큼 앞에서 고르고, 목록을 넘으면 마지막 값을 쓴다 */
        val backoffSec: List<Long> = listOf(60, 300, 900, 1800, 3600),
        /** SENDING 으로 굳은 행을 PENDING 으로 되돌리는 기준(초) */
        val stuckAfterSec: Long = 300,
        /** 발송이 끝난 대기열 행 보존일. 기록은 tb_alm_send_log 에 영구 보존된다 */
        val doneRetentionDays: Int = 7,
    ) {
        /** 다음 시도까지 기다릴 초. 시도 횟수는 1부터 센다 */
        fun backoffFor(tryCnt: Int): Long =
            backoffSec.getOrElse(tryCnt - 1) { backoffSec.lastOrNull() ?: 60L }
    }

    data class Escalation(val enabled: Boolean = true)

    /**
     * 야간 구간. 수신자·그룹의 `night_recv=false` 인 사람은 이 구간에 보내지 않는다.
     *
     * 조건의 유효 시간대(ALM_WINDOW)와는 다른 기준이다.
     * 그쪽은 "이 조건을 언제 보낼지", 이쪽은 "이 사람이 언제 받을지" 다.
     */
    data class Night(
        val from: String = "22:00",
        val to: String = "06:00",
    ) {
        /** 자정을 넘는 구간(22:00~06:00)이므로 단순 비교로는 판정할 수 없다 */
        fun contains(time: LocalTime): Boolean {
            val f = LocalTime.parse(from)
            val t = LocalTime.parse(to)
            return if (f <= t) time >= f && time < t else time >= f || time < t
        }
    }

    data class Message(
        /** LOG(발송 내용을 로그로 출력) | SMTP(실제 메일 발송) */
        val mailMode: String = "LOG",
        val fromAddress: String = "no-reply@dwje.co.kr",
        val fromName: String = "덕우전자 AX",
        val subjectPrefix: String = "[덕우전자 AX]",
        val webBaseUrl: String = "http://localhost:8090",
        /**
         * 조건에 `blind_field_key` 가 걸려 있으면 수신자의 부서 데이터 권한을 확인해
         * 본문의 실측치를 가린다.
         *
         * 끄면 권한이 없는 사람에게도 값이 그대로 나간다. 메일은 화면과 달리 권한 검사를
         * 통과해서 나가는 경로가 아니라서, 여기서 안 가리면 데이터 접근 권한이 메일로 샌다.
         */
        val maskByRecipient: Boolean = true,
    )
}
