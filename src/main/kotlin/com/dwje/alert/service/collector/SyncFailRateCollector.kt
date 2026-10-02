package com.dwje.alert.service.collector

import com.dwje.alert.model.CollectDef
import com.dwje.alert.model.MetricPoint
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/** 완료된 테이블 작업과 점검 실패 실행을 합산하여 이관 실패율을 계산합니다. */
@Component
class SyncFailRateCollector(private val jdbc: NamedParameterJdbcTemplate) : MetricCollector {
    override val metricCd = "SYNC_FAIL_RATE"

    override fun collect(def: CollectDef, from: OffsetDateTime, to: OffsetDateTime): List<MetricPoint> {
        val sql = """
            WITH jobs AS (
                SELECT count(*) AS total, count(*) FILTER (WHERE j.state_cd = 'FAIL') AS failed
                  FROM ax.tb_sync_job j
                  LEFT JOIN ax.tb_sync_run r ON r.run_id = j.run_id
                 WHERE j.ended_at >= :from AND j.ended_at < :to
                   AND j.state_cd IN ('DONE', 'RETRY_DONE', 'FAIL', 'ABORTED')
                   AND (r.mode_cd IS NULL OR r.mode_cd <> 'GROUPWARE')
            ), preflight AS (
                SELECT count(*) * (SELECT count(*) FROM ax.tb_sync_map WHERE use_flg = 'Y') AS failed
                  FROM ax.tb_sync_run
                 WHERE state_cd = 'PREFLIGHT_FAIL' AND mode_cd <> 'GROUPWARE'
                   AND ended_at >= :from AND ended_at < :to
            )
            SELECT round((jobs.failed + preflight.failed)::numeric
                         / nullif(jobs.total + preflight.failed, 0) * 100, 4) AS value
              FROM jobs CROSS JOIN preflight
        """.trimIndent()
        return jdbc.query(sql, MapSqlParameterSource().addValue("from", from).addValue("to", to)) { rs, _ ->
            rs.getBigDecimal("value")?.let { MetricPoint(measuredAt = to, value = it) }
        }.filterNotNull()
    }
}
