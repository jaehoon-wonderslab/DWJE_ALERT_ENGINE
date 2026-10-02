package com.dwje.alert.service.collector

import com.dwje.alert.config.AlertProperties
import com.dwje.alert.model.CollectDef
import com.dwje.alert.model.MetricPoint
import com.dwje.alert.model.ScopeDim
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * 공정 불량률 (PROC_DEFECT_RATE) — **불량수량 ÷ (양품 + 불량) × 100**.
 *
 * 원천은 MES 생산 실적(mes.tb_pop_label_hist)이다. 이관 엔진이 채우는 표라 값이 실제로 있다.
 *
 * [이 수집기는 지표가 등록돼 있을 때만 돈다]
 * 2026-09-16 현재 ax.tb_met_metric_std 에 PROC_DEFECT_RATE 가 없다. 지표 등록은
 * SY-13 [지표 측정 데이터 관리] 화면의 일이라 엔진이 마음대로 만들지 않는다.
 * 화면에서 등록하고 ax.tb_met_metric_collect 를 켜면 그때부터 이 수집기가 붙는다.
 *
 * 집계 단위는 수집 정의의 dim_cd 를 따른다 (EQPT 설비별 · WC 공정별).
 */
@Component
class DefectRateCollector(
    private val jdbc: NamedParameterJdbcTemplate,
    private val props: AlertProperties,
) : MetricCollector {

    override val metricCd = "PROC_DEFECT_RATE"

    override fun collect(def: CollectDef, from: OffsetDateTime, to: OffsetDateTime): List<MetricPoint> {
        // 집계 단위는 코드가 정한 값(ScopeDim)에서만 나오므로 문자열을 조립해도
        // 외부 입력이 섞이지 않는다.
        val byEqpt = def.dimCd == ScopeDim.EQPT
        val groupCols = if (byEqpt) "h.plant_cd, h.eqpt_cd, h.wc_cd" else "h.plant_cd, h.wc_cd"
        val eqptSel = if (byEqpt) "h.eqpt_cd" else "NULL::common.d_eqpt_cd AS eqpt_cd"
        val eqptFilter = if (byEqpt) "AND h.eqpt_cd IS NOT NULL" else ""

        val sql = """
            SELECT h.plant_cd,
                   h.wc_cd,
                   $eqptSel,
                   sum(coalesce(h.normal, 0)) AS ok_qty,
                   sum(coalesce(h.defect, 0)) AS ng_qty,
                   round(
                       sum(coalesce(h.defect, 0))
                       / nullif(sum(coalesce(h.normal, 0)) + sum(coalesce(h.defect, 0)), 0) * 100
                   , 4) AS defect_rate
              FROM mes.tb_pop_label_hist h
             WHERE h.del_flg = 'N'
               AND h.ins_date >= (:from::timestamptz AT TIME ZONE :tz)
               AND h.ins_date <  (:to::timestamptz   AT TIME ZONE :tz)
               $eqptFilter
             GROUP BY $groupCols
            HAVING sum(coalesce(h.normal, 0)) + sum(coalesce(h.defect, 0)) > 0
             ORDER BY 1, 2
             LIMIT :limit
        """.trimIndent()

        return jdbc.query(
            sql,
            MapSqlParameterSource()
                .addValue("from", from)
                .addValue("to", to)
                .addValue("tz", props.schedule.timezone)
                .addValue("limit", props.collect.maxPointsPerRun),
        ) { rs, _ ->
            MetricPoint(
                measuredAt = to,
                value = rs.getBigDecimal("defect_rate"),
                plantCd = rs.getString("plant_cd"),
                wcCd = rs.getString("wc_cd"),
                eqptCd = rs.getString("eqpt_cd"),
                srcCd = "MES",
                remark = "양품 ${rs.getBigDecimal("ok_qty").toPlainString()} · 불량 ${rs.getBigDecimal("ng_qty").toPlainString()}",
            )
        }
    }
}
