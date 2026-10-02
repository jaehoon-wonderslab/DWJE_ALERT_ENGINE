package com.dwje.alert.service.collector

import com.dwje.alert.config.AlertProperties
import com.dwje.alert.model.CollectDef
import com.dwje.alert.model.MetricPoint
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * 설비 가동률 (EQPT_UPTIME_RATE).
 *
 * 계산식은 지표 기준(SY-13)에 적힌 그대로다 — **가동시간 ÷ 조업시간 × 100**.
 *   · 조업시간 = 수집 구간의 길이
 *   · 비가동시간 = ax.tb_prod_downtime 구간 중 수집 구간과 겹치는 만큼
 *   · 가동시간 = 조업시간 − 비가동시간
 *
 * [대상 설비를 어떻게 고르는가]
 * 설비 마스터에는 1,490대가 있지만 실제로 돌고 있는 것은 그중 일부다(최근 24시간 기준 약 550대).
 * 전부 수집하면 5분 주기에서 하루 40만 행이 쌓이는데, 그 대부분은 몇 년째 안 쓰는 설비의
 * "가동률 100%" 다. 그래서 **최근 24시간에 생산 실적이 있는 설비** 를 운영 중인 설비로 보고,
 * 여기에 **지금 비가동이 걸려 있는 설비** 를 더한다.
 *
 * 두 번째 항이 중요하다. 생산 실적만으로 고르면 아침부터 멈춰 선 설비가 대상에서 빠져
 * "가동률이 떨어졌다" 는 알림이 정작 그 설비에서 나지 않는다.
 */
@Component
class EqptUptimeCollector(
    private val jdbc: NamedParameterJdbcTemplate,
    private val props: AlertProperties,
) : MetricCollector {

    private val log = LoggerFactory.getLogger(javaClass)

    override val metricCd = "EQPT_UPTIME_RATE"

    override fun collect(def: CollectDef, from: OffsetDateTime, to: OffsetDateTime): List<MetricPoint> {
        val sql = """
            WITH win AS (
                SELECT :from::timestamptz AS f, :to::timestamptz AS t
            ),
            fleet AS (
                -- 최근 24시간 실적이 있는 설비 = 지금 돌고 있는 설비
                SELECT DISTINCT h.plant_cd, h.eqpt_cd
                  FROM mes.tb_pop_label_hist h, win w
                 WHERE h.eqpt_cd IS NOT NULL
                   AND h.del_flg = 'N'
                   AND h.ins_date >= ((w.t - interval '24 hours') AT TIME ZONE :tz)
                   AND h.ins_date <   (w.t AT TIME ZONE :tz)
                UNION
                -- 구간에 비가동이 걸린 설비 — 멈춰서 실적이 없는 설비를 빠뜨리지 않기 위함
                SELECT DISTINCT d.plant_cd, d.eqpt_cd
                  FROM ax.tb_prod_downtime d, win w
                 WHERE d.stop_at < w.t
                   AND coalesce(d.resume_at, w.t) > w.f
            ),
            down AS (
                SELECT d.plant_cd, d.eqpt_cd,
                       sum(extract(epoch FROM (
                             least(coalesce(d.resume_at, w.t), w.t) - greatest(d.stop_at, w.f)
                       ))) AS down_sec
                  FROM ax.tb_prod_downtime d, win w
                 WHERE d.stop_at < w.t
                   AND coalesce(d.resume_at, w.t) > w.f
                 GROUP BY d.plant_cd, d.eqpt_cd
            )
            SELECT f.plant_cd,
                   f.eqpt_cd,
                   wc.wc_cd,
                   round(
                       greatest(0, extract(epoch FROM (w.t - w.f)) - coalesce(dn.down_sec, 0))
                       / nullif(extract(epoch FROM (w.t - w.f)), 0) * 100
                   , 4) AS uptime_rate,
                   round(coalesce(dn.down_sec, 0) / 60.0, 1) AS down_min
              FROM fleet f
              CROSS JOIN win w
              LEFT JOIN down dn ON dn.plant_cd = f.plant_cd AND dn.eqpt_cd = f.eqpt_cd
              LEFT JOIN LATERAL (
                    SELECT x.wc_cd
                      FROM mes.tb_md_eqpt_by_workcenter x
                     WHERE x.plant_cd = f.plant_cd AND x.eqpt_cd = f.eqpt_cd
                     ORDER BY x.start_flg DESC, x.wc_cd
                     LIMIT 1
              ) wc ON true
             ORDER BY f.plant_cd, f.eqpt_cd
             LIMIT :limit
        """.trimIndent()

        val rows = jdbc.query(
            sql,
            MapSqlParameterSource()
                .addValue("from", from)
                .addValue("to", to)
                .addValue("tz", props.schedule.timezone)
                .addValue("limit", props.collect.maxPointsPerRun),
        ) { rs, _ ->
            val downMin = rs.getBigDecimal("down_min")
            MetricPoint(
                measuredAt = to,
                value = rs.getBigDecimal("uptime_rate"),
                plantCd = rs.getString("plant_cd"),
                wcCd = rs.getString("wc_cd"),
                eqptCd = rs.getString("eqpt_cd"),
                srcCd = "BATCH",
                remark = if (downMin != null && downMin.signum() > 0) "비가동 ${downMin}분" else null,
            )
        }

        if (rows.size >= props.collect.maxPointsPerRun) {
            log.warn(
                "가동률 수집이 상한 {}건에 걸렸습니다. 일부 설비가 빠졌습니다 — " +
                    "alert.collect.max-points-per-run 을 늘리거나 수집 주기를 늘리십시오.",
                props.collect.maxPointsPerRun,
            )
        }
        return rows
    }
}
