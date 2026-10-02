package com.dwje.alert.repository

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 발생한 알림 (ax.tb_alm_alert).
 *
 * 알림 행은 엔진이 만들고, 확인 처리(ack)는 사람이 AL-01 화면에서 한다.
 * 엔진은 사람이 한 확인을 되돌리지 않는다 — 값이 정상으로 돌아와도 resolved_at 만 찍는다.
 */
@Repository
class AlertRepository(private val jdbc: NamedParameterJdbcTemplate) {

    data class EscalationTarget(
        val alertId: Long,
        val condId: Int?,
        val title: String,
        val severity: String,
        val escLevel: Short,
        val toGroupId: Int?,
        val levelNm: String,
        val afterMin: Int,
    )

    /**
     * 억제 창 안에 이미 낸 알림이 있는가.
     *
     * 있으면 새 알림을 만들지 않고 그 행의 hit_cnt 를 올린다. 억제를 "알림을 아예 안 만든다"
     * 로 처리하면 30분 동안 12번 터진 사실이 사라진다.
     */
    fun findLiveByDedup(dedupKey: String, since: OffsetDateTime): Long? = jdbc.query(
        """
        SELECT alert_id
          FROM ax.tb_alm_alert
         WHERE test_flg = 'N' AND dedup_key = :dedupKey
           AND occurred_at >= :since
         ORDER BY occurred_at DESC
         LIMIT 1
        """.trimIndent(),
        MapSqlParameterSource().addValue("dedupKey", dedupKey).addValue("since", since),
    ) { rs, _ -> rs.getLong("alert_id") }.firstOrNull()

    /** 같은 달력일에 이미 낸 알림이 있는가 (ALM_DEDUP = DAY_ONCE) */
    fun findLiveByDedupToday(dedupKey: String): Long? = jdbc.query(
        """
        SELECT alert_id
          FROM ax.tb_alm_alert
         WHERE test_flg = 'N' AND dedup_key = :dedupKey
           AND occurred_at >= date_trunc('day', now())
         ORDER BY occurred_at DESC
         LIMIT 1
        """.trimIndent(),
        MapSqlParameterSource("dedupKey", dedupKey),
    ) { rs, _ -> rs.getLong("alert_id") }.firstOrNull()

    fun insert(
        condId: Int,
        metricId: Int?,
        severity: String,
        title: String,
        occurredAt: OffsetDateTime,
        metricValue: BigDecimal?,
        threshold: BigDecimal?,
        evidence: String?,
        scopeKey: String,
        dedupKey: String,
        targetDesc: String?,
        plantCd: String?,
        wcCd: String?,
        eqptCd: String?,
        itemCd: String?,
        moldCd: String?,
    ): Long {
        val sql = """
            INSERT INTO ax.tb_alm_alert
                   (cond_id, metric_id, severity_cd, title, occurred_at,
                    metric_value, threshold_val, evidence_desc,
                    plant_cd, wc_cd, eqpt_cd, item_cd, mold_cd,
                    target_desc, scope_key, dedup_key, hit_cnt, last_hit_at)
            VALUES (:condId, :metricId, :severity, :title, :occurredAt,
                    :metricValue, :threshold, :evidence,
                    :plantCd, :wcCd, :eqptCd, :itemCd, :moldCd,
                    :targetDesc, :scopeKey, :dedupKey, 1, :occurredAt)
            RETURNING alert_id
        """.trimIndent()
        return jdbc.queryForObject(
            sql,
            MapSqlParameterSource()
                .addValue("condId", condId)
                .addValue("metricId", metricId)
                .addValue("severity", severity)
                .addValue("title", title.take(200))
                .addValue("occurredAt", occurredAt)
                .addValue("metricValue", metricValue)
                .addValue("threshold", threshold)
                .addValue("evidence", evidence?.take(300))
                .addValue("plantCd", plantCd)
                .addValue("wcCd", wcCd)
                .addValue("eqptCd", eqptCd)
                .addValue("itemCd", itemCd)
                .addValue("moldCd", moldCd)
                .addValue("targetDesc", targetDesc?.take(200))
                .addValue("scopeKey", scopeKey.take(100))
                .addValue("dedupKey", dedupKey.take(200)),
            Long::class.java,
        )!!
    }

    /** 억제 창 안에서 다시 걸렸다 — 알림은 그대로 두고 재발 사실만 남긴다 */
    fun bumpHit(alertId: Long, at: OffsetDateTime, value: BigDecimal?) {
        jdbc.update(
            """
            UPDATE ax.tb_alm_alert
               SET hit_cnt      = hit_cnt + 1,
                   last_hit_at  = :at,
                   metric_value = coalesce(:value, metric_value)
             WHERE alert_id = :alertId
            """.trimIndent(),
            MapSqlParameterSource().addValue("alertId", alertId).addValue("at", at).addValue("value", value),
        )
    }

    /**
     * 값이 정상으로 돌아왔다.
     *
     * 확인 상태(ack_state_cd)는 건드리지 않는다. 사람이 안 봐도 상황은 풀릴 수 있고,
     * 그렇다고 '확인됨' 으로 바꾸면 아무도 보지 않은 알림이 처리된 것처럼 남는다.
     */
    fun markResolved(alertId: Long, at: OffsetDateTime): Int = jdbc.update(
        "UPDATE ax.tb_alm_alert SET resolved_at = :at WHERE alert_id = :alertId AND resolved_at IS NULL AND test_flg = 'N'",
        MapSqlParameterSource().addValue("alertId", alertId).addValue("at", at),
    )

    /**
     * 승격 대상 — 확인되지 않은 채 after_min 이 지났고 아직 그 단계로 안 올라간 알림.
     *
     * 규칙은 ax.tb_alm_escalation_rule 이 쥐고 있고 조건별 적용 여부는
     * ax.tb_alm_cond_escalation.is_on 이 정한다. 엔진은 시간만 본다.
     */
    fun findEscalationTargets(limit: Int): List<EscalationTarget> {
        val sql = """
            SELECT a.alert_id, a.cond_id, a.title, a.severity_cd, a.esc_level,
                   r.esc_level AS to_level, r.to_group_id, r.level_nm, r.after_min
              FROM ax.tb_alm_alert a
              JOIN ax.tb_alm_cond_escalation ce ON ce.cond_id = a.cond_id AND ce.is_on
              JOIN ax.tb_alm_escalation_rule r  ON r.esc_rule_id = ce.esc_rule_id AND r.use_flg = 'Y'
             WHERE a.test_flg = 'N' AND a.ack_state_cd = 'OPEN'
               AND a.resolved_at IS NULL
               AND a.esc_level < r.esc_level
               AND now() >= a.occurred_at + make_interval(mins => r.after_min)
               AND (r.severity_filter IS NULL OR r.severity_filter = a.severity_cd)
             ORDER BY r.esc_level, a.occurred_at
             LIMIT :limit
        """.trimIndent()
        return jdbc.query(sql, MapSqlParameterSource("limit", limit)) { rs, _ ->
            EscalationTarget(
                alertId = rs.getLong("alert_id"),
                condId = rs.getObject("cond_id") as? Int,
                title = rs.getString("title"),
                severity = rs.getString("severity_cd"),
                escLevel = rs.getShort("to_level"),
                toGroupId = rs.getObject("to_group_id") as? Int,
                levelNm = rs.getString("level_nm"),
                afterMin = rs.getInt("after_min"),
            )
        }
    }

    /** 승격 단계를 올린다. 이미 그 단계 이상이면 아무것도 바꾸지 않는다 */
    fun raiseEscLevel(alertId: Long, level: Short): Int = jdbc.update(
        "UPDATE ax.tb_alm_alert SET esc_level = :level WHERE alert_id = :alertId AND esc_level < :level",
        MapSqlParameterSource().addValue("alertId", alertId).addValue("level", level.toInt()),
    )
}
