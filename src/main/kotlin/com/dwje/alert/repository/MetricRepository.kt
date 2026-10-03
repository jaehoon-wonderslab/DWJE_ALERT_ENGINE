package com.dwje.alert.repository

import com.dwje.alert.model.CollectDef
import com.dwje.alert.model.MetricPoint
import com.dwje.alert.model.ScopeDim
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 판정할 값을 읽고 쓴다 (ax.tb_met_metric_value · tb_met_metric_collect).
 *
 * 이 표가 비어 있으면 조건이 아무리 잘 등록돼 있어도 비교할 값이 없다.
 * 실제로 엔진을 만들기 전 상태가 그랬다 — tb_met_metric_value 0행.
 */
@Repository
class MetricRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /** 지표 한 대상의 현재 읽은 값 */
    data class Reading(
        val scopeKey: String,
        val value: BigDecimal,
        val measuredAt: OffsetDateTime,
        /** 구간 평균 (지속 조건이 MA120 처럼 이동평균일 때 쓴다) */
        val avgValue: BigDecimal,
        /** 직전 값. 변화율(RATE) 비교에 쓴다. 값이 하나뿐이면 null */
        val prevValue: BigDecimal?,
        val sampleCnt: Int,
        /** 최신 값이 쌓인 공장·공정. 알림 행에 그대로 싣는다 */
        val plantCd: String? = null,
        val wcCd: String? = null,
    )

    /**
     * 이 지표가 쌓이는 단위 (tb_met_metric_collect.dim_cd). 수집 정의가 없으면 null.
     *
     * 엔진은 항상 이 수집 단위를 따릅니다. 설비별로 쌓인 값을 NONE으로 묶으면
     * 같은 시각의 수백 행 중 아무 설비 하나의 값으로 판정하게 됩니다.
     */
    fun collectDimOf(metricId: Int): ScopeDim? = jdbc.query(
        "SELECT dim_cd FROM ax.tb_met_metric_collect WHERE metric_id = :metricId",
        MapSqlParameterSource("metricId", metricId),
    ) { rs, _ -> ScopeDim.of(rs.getString("dim_cd")) }.firstOrNull()

    // ── 수집 정의 ────────────────────────────────────────────────────────────────────

    fun findEnabledCollectDefs(): List<CollectDef> {
        val sql = """
            SELECT c.metric_id, c.collect_mode_cd, c.collector_cd, c.dim_cd,
                   c.interval_sec, c.lookback_min, c.sql_text,
                   c.last_run_at, c.last_value_at,
                   m.metric_cd, m.metric_nm, m.std_val, m.warn_val, m.crit_val
              FROM ax.tb_met_metric_collect c
              JOIN ax.tb_met_metric_std m ON m.metric_id = c.metric_id
             WHERE c.use_flg = 'Y' AND m.use_flg = 'Y'
             ORDER BY c.metric_id
        """.trimIndent()
        return jdbc.query(sql, emptyMap<String, Any>()) { rs, _ ->
            CollectDef(
                metricId = rs.getInt("metric_id"),
                metricCd = rs.getString("metric_cd"),
                metricNm = rs.getString("metric_nm"),
                modeCd = rs.getString("collect_mode_cd"),
                collectorCd = rs.getString("collector_cd") ?: rs.getString("metric_cd"),
                dimCd = ScopeDim.of(rs.getString("dim_cd")),
                intervalSec = rs.getInt("interval_sec"),
                lookbackMin = rs.getInt("lookback_min"),
                sqlText = rs.getString("sql_text"),
                stdVal = rs.getBigDecimal("std_val"),
                warnVal = rs.getBigDecimal("warn_val"),
                critVal = rs.getBigDecimal("crit_val"),
                lastRunAt = rs.getObject("last_run_at", OffsetDateTime::class.java),
                lastValueAt = rs.getObject("last_value_at", OffsetDateTime::class.java),
            )
        }
    }

    /**
     * 수집 결과를 기록한다.
     *
     * 실패해도 last_run_at 은 갱신한다. 갱신하지 않으면 매 틱마다 같은 실패를 반복해
     * 로그가 그것으로 가득 찬다. 대신 last_value_at 은 그대로 둔다 —
     * 그 값이 낡으면 엔진이 판정을 멈추는 근거(STALE)이기 때문이다.
     */
    fun markCollected(metricId: Int, runAt: OffsetDateTime, valueAt: OffsetDateTime?, error: String?) {
        jdbc.update(
            """
            UPDATE ax.tb_met_metric_collect
               SET last_run_at   = :runAt,
                   last_value_at = coalesce(:valueAt, last_value_at),
                   last_error    = :error,
                   upd_date      = now(),
                   upd_user      = 'ENGINE'
             WHERE metric_id = :metricId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("metricId", metricId)
                .addValue("runAt", runAt)
                .addValue("valueAt", valueAt)
                .addValue("error", error?.take(500)),
        )
    }

    // ── 값 적재 ──────────────────────────────────────────────────────────────────────

    /** 수집한 값을 한 번에 적재한다 */
    fun insertPoints(def: CollectDef, points: List<MetricPoint>): Int {
        if (points.isEmpty()) return 0
        val sql = """
            INSERT INTO ax.tb_met_metric_value
                   (metric_id, measured_at, metric_value, judge_cd,
                    plant_cd, wc_cd, eqpt_cd, mold_cd, item_cd, product_id, src_cd, remark)
            VALUES (:metricId, :measuredAt, :value, :judge,
                    :plantCd, :wcCd, :eqptCd, :moldCd, :itemCd, :productId, :srcCd, :remark)
        """.trimIndent()
        val batch = points.map { p ->
            MapSqlParameterSource()
                .addValue("metricId", def.metricId)
                .addValue("measuredAt", p.measuredAt)
                .addValue("value", p.value)
                .addValue("judge", def.judge(p.value))
                .addValue("plantCd", p.plantCd)
                .addValue("wcCd", p.wcCd)
                .addValue("eqptCd", p.eqptCd)
                .addValue("moldCd", p.moldCd)
                .addValue("itemCd", p.itemCd)
                .addValue("productId", p.productId)
                .addValue("srcCd", p.srcCd)
                .addValue("remark", p.remark?.take(300))
        }.toTypedArray()
        return jdbc.batchUpdate(sql, batch).sum()
    }

    // ── 판정용 조회 ──────────────────────────────────────────────────────────────────

    /**
     * 대상별 최신 값 · 구간 평균 · 직전 값을 한 번에 읽는다.
     *
     * 세 가지를 따로 조회하면 조건 하나에 질의가 3번 나간다. 조건이 수십 개면
     * 틱마다 수백 번이다. 윈도 함수로 한 번에 끝낸다.
     *
     * 대상 키 식은 [ScopeDim] 에서만 나오므로 문자열을 조립해도 외부 입력이 섞이지 않는다.
     */
    fun readValues(metricId: Int, dim: ScopeDim, since: OffsetDateTime): Map<String, Reading> {
        val keyExpr = when (dim) {
            ScopeDim.NONE -> "'*'"
            ScopeDim.EQPT -> "eqpt_cd"
            ScopeDim.WC -> "wc_cd"
            ScopeDim.ITEM -> "item_cd"
            ScopeDim.MOLD -> "mold_cd"
            ScopeDim.PRODUCT -> "product_id::text"
        }
        val notNull = if (dim == ScopeDim.NONE) "" else "AND $keyExpr IS NOT NULL"

        val sql = """
            WITH v AS (
                SELECT $keyExpr AS scope_key, metric_value, measured_at, plant_cd, wc_cd,
                       row_number() OVER (PARTITION BY $keyExpr ORDER BY measured_at DESC) AS rn,
                       avg(metric_value) OVER (PARTITION BY $keyExpr)                      AS avg_value,
                       count(*)          OVER (PARTITION BY $keyExpr)                      AS sample_cnt
                  FROM ax.tb_met_metric_value
                 WHERE metric_id = :metricId
                   AND measured_at >= :since
                   $notNull
            )
            SELECT v.scope_key, v.metric_value, v.measured_at, v.avg_value, v.sample_cnt,
                   v.plant_cd, v.wc_cd,
                   (SELECT p.metric_value FROM v p
                     WHERE p.scope_key = v.scope_key AND p.rn = 2) AS prev_value
              FROM v
             WHERE v.rn = 1
        """.trimIndent()

        return jdbc.query(
            sql,
            MapSqlParameterSource().addValue("metricId", metricId).addValue("since", since),
        ) { rs, _ ->
            Reading(
                scopeKey = rs.getString("scope_key"),
                value = rs.getBigDecimal("metric_value"),
                measuredAt = rs.getObject("measured_at", OffsetDateTime::class.java),
                avgValue = rs.getBigDecimal("avg_value"),
                prevValue = rs.getBigDecimal("prev_value"),
                sampleCnt = rs.getInt("sample_cnt"),
                plantCd = rs.getString("plant_cd"),
                wcCd = rs.getString("wc_cd"),
            )
        }.associateBy { it.scopeKey }
    }

    /**
     * 이 지표의 값이 마지막으로 쌓인 시각.
     *
     * 수집이 멈췄는지 보는 값이다. 낡은 값으로 판정하면 이미 끝난 이상이 계속 나가거나
     * 진짜 이상을 정상으로 본다 — 둘 다 알림을 못 믿게 만든다.
     */
    fun lastValueAt(metricId: Int): OffsetDateTime? = jdbc.query(
        "SELECT max(measured_at) AS at FROM ax.tb_met_metric_value WHERE metric_id = :metricId",
        MapSqlParameterSource("metricId", metricId),
    ) { rs, _ -> rs.getObject("at", OffsetDateTime::class.java) }.firstOrNull()
}
