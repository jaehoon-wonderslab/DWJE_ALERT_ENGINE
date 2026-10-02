package com.dwje.alert.service.collector

import com.dwje.alert.model.CollectDef
import com.dwje.alert.model.MetricPoint
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/** 그룹웨어를 제외한 마지막 정상 테이블 이관 종료부터 경과한 분을 계산합니다. */
@Component
class SyncStaleCollector(private val jdbc: NamedParameterJdbcTemplate) : MetricCollector {
    override val metricCd = "SYNC_STALE_MIN"

    override fun collect(def: CollectDef, from: OffsetDateTime, to: OffsetDateTime): List<MetricPoint> {
        val sql = """
            SELECT round(extract(epoch FROM (:to - max(j.ended_at))) / 60.0, 4) AS value
              FROM ax.tb_sync_job j
              LEFT JOIN ax.tb_sync_run r ON r.run_id = j.run_id
             WHERE j.state_cd IN ('DONE', 'RETRY_DONE') AND j.ended_at <= :to
               AND (r.mode_cd IS NULL OR r.mode_cd <> 'GROUPWARE')
        """.trimIndent()
        return jdbc.query(sql, MapSqlParameterSource("to", to)) { rs, _ ->
            rs.getBigDecimal("value")?.let { MetricPoint(measuredAt = to, value = it) }
        }.filterNotNull()
    }
}
