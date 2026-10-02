package com.dwje.alert.repository

import com.dwje.alert.model.RunState
import com.dwje.alert.model.RunTrigger
import com.dwje.alert.model.TickResult
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * 엔진 실행 이력 (ax.tb_alm_eval_run).
 *
 * **아무 일도 없던 틱은 남기지 않는다.** 1분 주기면 하루 1,440행이 쌓여 정작 봐야 할
 * 실행이 묻힌다. 알림·발송·실패가 하나라도 있을 때만 남기고, 조용한 구간은
 * 시간당 1행(HEARTBEAT)만 남긴다. 이관 엔진의 큐 폴링에서 같은 판단을 했다.
 */
@Repository
class EvalRunRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /** 실행 1건을 통째로 기록한다. 틱이 짧아 시작·종료를 나눠 쓸 이유가 없다 */
    fun record(
        runId: String,
        startedAt: OffsetDateTime,
        endedAt: OffsetDateTime,
        state: RunState,
        result: TickResult,
        trigger: RunTrigger,
        triggeredBy: String?,
        hostName: String,
        engineVersion: String,
        message: String?,
    ) {
        val sql = """
            INSERT INTO ax.tb_alm_eval_run
                   (run_id, started_at, ended_at, duration_ms, state_cd,
                    cond_cnt, eval_cnt, raise_cnt, suppress_cnt, skip_cnt,
                    queued_cnt, sent_cnt, fail_cnt,
                    triggered_by_cd, triggered_by, host_name, engine_version, message)
            VALUES (:runId, :startedAt, :endedAt, :durationMs, :state,
                    :condCnt, :evalCnt, :raiseCnt, :suppressCnt, :skipCnt,
                    :queuedCnt, :sentCnt, :failCnt,
                    :trigger, :triggeredBy, :hostName, :version, :message)
            ON CONFLICT (run_id) DO NOTHING
        """.trimIndent()
        jdbc.update(
            sql,
            MapSqlParameterSource()
                .addValue("runId", runId.take(30))
                .addValue("startedAt", startedAt)
                .addValue("endedAt", endedAt)
                .addValue("durationMs", java.time.Duration.between(startedAt, endedAt).toMillis().toInt())
                .addValue("state", state.name)
                .addValue("condCnt", result.condCnt)
                .addValue("evalCnt", result.evalCnt)
                .addValue("raiseCnt", result.raiseCnt)
                .addValue("suppressCnt", result.suppressCnt)
                .addValue("skipCnt", result.skipCnt)
                .addValue("queuedCnt", result.queuedCnt)
                .addValue("sentCnt", result.sentCnt)
                .addValue("failCnt", result.failCnt)
                .addValue("trigger", trigger.name)
                .addValue("triggeredBy", triggeredBy)
                .addValue("hostName", hostName.take(100))
                .addValue("version", engineVersion.take(20))
                .addValue("message", message?.take(2000)),
        )
    }

    /** 마지막으로 이력을 남긴 시각. 조용한 구간의 요약 행 주기를 재는 데 쓴다 */
    fun lastRecordedAt(): OffsetDateTime? = jdbc.query(
        "SELECT max(started_at) AS at FROM ax.tb_alm_eval_run",
        MapSqlParameterSource(),
    ) { rs, _ -> rs.getObject("at", OffsetDateTime::class.java) }.firstOrNull()

    /**
     * 비정상 종료로 RUNNING 에 남은 실행을 정리한다.
     * 남겨 두면 엔진 상태 조회가 "지금도 돌고 있다" 고 잘못 답한다.
     */
    fun abortStale(): Int = jdbc.update(
        """
        UPDATE ax.tb_alm_eval_run
           SET state_cd = 'FAIL',
               ended_at = coalesce(ended_at, now()),
               message  = coalesce(message, '엔진이 비정상 종료되어 정리됨')
         WHERE state_cd = 'RUNNING'
        """.trimIndent(),
        MapSqlParameterSource(),
    )

    /** V35 가 적용되지 않은 DB 에서 엔진이 무엇을 못 하는지 알려 주기 위한 확인 */
    fun isSchemaReady(): Boolean = jdbc.queryForObject(
        """
        SELECT count(*) = 4
          FROM information_schema.tables
         WHERE table_schema = 'ax'
           AND table_name IN ('tb_alm_cond_state', 'tb_alm_send_queue',
                              'tb_alm_eval_run', 'tb_met_metric_collect')
        """.trimIndent(),
        MapSqlParameterSource(),
        Boolean::class.java,
    ) ?: false
}
