package com.dwje.alert.repository

import com.dwje.alert.model.AlertCondition
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * 판정할 조건을 읽는다 (ax.tb_alm_cond).
 *
 * 엔진은 조건을 **읽기만** 한다. 등록·수정·삭제는 언제나 SY-04 화면이다.
 * 유일한 예외가 last_eval_at 인데, 이것은 조건 내용이 아니라 "엔진이 언제 봤는가" 라서
 * 화면이 쓸 값이 아니다.
 */
@Repository
class ConditionRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * 활성 조건 전체.
     *
     * 채널·수신그룹·개별대상은 행을 늘리지 않도록 배열로 모아 온다.
     * 조인으로 펼치면 채널 2개 × 그룹 3개인 조건이 6행으로 늘어 조건 수를 잘못 센다.
     */
    fun findActive(condIds: Collection<Int> = emptyList()): List<AlertCondition> {
        val sql = """
            SELECT c.cond_id, c.cond_nm, c.severity_cd, c.metric_id, c.metric_desc,
                   c.op_cd, c.threshold_val, c.threshold_text, c.threshold_unit,
                   c.duration_cd, c.target_scope_cd, c.target_desc,
                   c.window_cd, c.dedup_cd, c.blind_field_key,
                   m.metric_cd, m.metric_nm, m.unit_cd,
                   (SELECT coalesce(array_agg(ch.channel_cd ORDER BY ch.channel_cd), '{}')
                      FROM ax.tb_alm_cond_channel ch WHERE ch.cond_id = c.cond_id)      AS channels,
                   (SELECT coalesce(array_agg(g.group_id ORDER BY g.group_id), '{}')
                      FROM ax.tb_alm_cond_group g WHERE g.cond_id = c.cond_id)          AS group_ids,
                   (SELECT coalesce(array_agg(t.target_cd ORDER BY t.target_cd), '{}')
                      FROM ax.tb_alm_cond_target t WHERE t.cond_id = c.cond_id)         AS pick_targets
              FROM ax.tb_alm_cond c
              LEFT JOIN ax.tb_met_metric_std m ON m.metric_id = c.metric_id
             WHERE c.use_flg = 'Y' AND c.window_cd <> 'ONCE'
               AND (:filtered = false OR c.cond_id = ANY (:condIds))
             ORDER BY c.cond_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("filtered", condIds.isNotEmpty())
            .addValue("condIds", condIds.toIntArray())

        return jdbc.query(sql, params) { rs, _ -> map(rs) }
    }

    /** 조건 한 건 (활성 여부와 무관 — 수동 평가는 중지된 조건도 확인할 수 있어야 한다) */
    fun findOne(condId: Int): AlertCondition? {
        val sql = """
            SELECT c.cond_id, c.cond_nm, c.severity_cd, c.metric_id, c.metric_desc,
                   c.op_cd, c.threshold_val, c.threshold_text, c.threshold_unit,
                   c.duration_cd, c.target_scope_cd, c.target_desc,
                   c.window_cd, c.dedup_cd, c.blind_field_key,
                   m.metric_cd, m.metric_nm, m.unit_cd,
                   (SELECT coalesce(array_agg(ch.channel_cd ORDER BY ch.channel_cd), '{}')
                      FROM ax.tb_alm_cond_channel ch WHERE ch.cond_id = c.cond_id)      AS channels,
                   (SELECT coalesce(array_agg(g.group_id ORDER BY g.group_id), '{}')
                      FROM ax.tb_alm_cond_group g WHERE g.cond_id = c.cond_id)          AS group_ids,
                   (SELECT coalesce(array_agg(t.target_cd ORDER BY t.target_cd), '{}')
                      FROM ax.tb_alm_cond_target t WHERE t.cond_id = c.cond_id)         AS pick_targets
              FROM ax.tb_alm_cond c
              LEFT JOIN ax.tb_met_metric_std m ON m.metric_id = c.metric_id
             WHERE c.cond_id = :condId
        """.trimIndent()
        return jdbc.query(sql, MapSqlParameterSource("condId", condId)) { rs, _ -> map(rs) }.firstOrNull()
    }

    /** 화면에 "마지막으로 본 시각" 을 보이기 위한 값. 판정 자체는 상태 표가 쥔다 */
    fun touchLastEval(condIds: Collection<Int>, at: OffsetDateTime) {
        if (condIds.isEmpty()) return
        jdbc.update(
            "UPDATE ax.tb_alm_cond SET last_eval_at = :at WHERE cond_id = ANY (:ids)",
            MapSqlParameterSource().addValue("at", at).addValue("ids", condIds.toIntArray()),
        )
    }

    private fun map(rs: ResultSet) = AlertCondition(
        condId = rs.getInt("cond_id"),
        name = rs.getString("cond_nm"),
        severity = rs.getString("severity_cd"),
        metricId = rs.getObject("metric_id") as? Int,
        metricCd = rs.getString("metric_cd"),
        metricNm = rs.getString("metric_nm"),
        metricDesc = rs.getString("metric_desc"),
        unitCd = rs.getString("unit_cd"),
        opCd = rs.getString("op_cd"),
        threshold = rs.getBigDecimal("threshold_val"),
        thresholdText = rs.getString("threshold_text"),
        thresholdUnit = rs.getString("threshold_unit"),
        durationCd = rs.getString("duration_cd"),
        targetScopeCd = rs.getString("target_scope_cd"),
        targetDesc = rs.getString("target_desc"),
        windowCd = rs.getString("window_cd"),
        dedupCd = rs.getString("dedup_cd"),
        blindFieldKey = rs.getString("blind_field_key"),
        channels = rs.textArray("channels"),
        groupIds = rs.intArray("group_ids"),
        pickTargets = rs.textArray("pick_targets"),
    )

    private fun ResultSet.textArray(col: String): List<String> =
        (getArray(col)?.array as? Array<*>)?.mapNotNull { it as? String } ?: emptyList()

    private fun ResultSet.intArray(col: String): List<Int> =
        (getArray(col)?.array as? Array<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
}
