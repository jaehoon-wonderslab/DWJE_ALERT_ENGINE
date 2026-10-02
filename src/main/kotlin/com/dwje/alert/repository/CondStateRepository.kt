package com.dwje.alert.repository

import com.dwje.alert.model.CondStateCd
import com.dwje.alert.model.CondStateRow
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 조건 × 대상의 평가 상태 (ax.tb_alm_cond_state).
 *
 * 엔진이 유일하게 상태를 들고 있는 곳이다. 여기가 비면 '10분 연속' 도 '30분 억제' 도
 * 계산할 수 없고, 엔진은 매 틱마다 처음 보는 것처럼 판정한다.
 */
@Repository
class CondStateRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /** 한 조건의 모든 대상 상태를 scope_key 로 찾아 쓰게 모아 온다 */
    fun findByCond(condId: Int): Map<String, CondStateRow> {
        val sql = """
            SELECT cond_id, scope_key, state_cd, last_value, last_eval_at,
                   breach_since, breach_cnt, last_alert_id, last_alert_at,
                   suppress_cnt, next_eval_at
              FROM ax.tb_alm_cond_state
             WHERE cond_id = :condId
        """.trimIndent()
        return jdbc.query(sql, MapSqlParameterSource("condId", condId)) { rs, _ ->
            CondStateRow(
                condId = rs.getInt("cond_id"),
                scopeKey = rs.getString("scope_key"),
                state = runCatching { CondStateCd.valueOf(rs.getString("state_cd")) }
                    .getOrDefault(CondStateCd.NORMAL),
                lastValue = rs.getBigDecimal("last_value"),
                lastEvalAt = rs.getObject("last_eval_at", OffsetDateTime::class.java),
                breachSince = rs.getObject("breach_since", OffsetDateTime::class.java),
                breachCnt = rs.getInt("breach_cnt"),
                lastAlertId = rs.getObject("last_alert_id") as? Long,
                lastAlertAt = rs.getObject("last_alert_at", OffsetDateTime::class.java),
                suppressCnt = rs.getInt("suppress_cnt"),
                nextEvalAt = rs.getObject("next_eval_at", OffsetDateTime::class.java),
            )
        }.associateBy { it.scopeKey }
    }

    /**
     * 판정 결과를 쓴다.
     *
     * `last_alert_id` / `last_alert_at` / `suppress_cnt` 는 여기서 건드리지 않는다 —
     * 알림을 실제로 낸 [markAlerted] · [markSuppressed] 만 고친다. 판정 UPSERT 가
     * 그 값까지 덮으면 억제 창이 매 틱 초기화돼 억제가 듣지 않는다.
     */
    fun upsertEvaluation(
        condId: Int,
        scopeKey: String,
        state: CondStateCd,
        value: BigDecimal?,
        evalAt: OffsetDateTime,
        breachSince: OffsetDateTime?,
        breachCnt: Int,
        nextEvalAt: OffsetDateTime,
    ) {
        val sql = """
            INSERT INTO ax.tb_alm_cond_state
                   (cond_id, scope_key, state_cd, last_value, last_eval_at,
                    breach_since, breach_cnt, next_eval_at, upd_date)
            VALUES (:condId, :scopeKey, :state, :value, :evalAt,
                    :breachSince, :breachCnt, :nextEvalAt, now())
            ON CONFLICT (cond_id, scope_key) DO UPDATE SET
                   state_cd     = EXCLUDED.state_cd,
                   last_value   = EXCLUDED.last_value,
                   last_eval_at = EXCLUDED.last_eval_at,
                   breach_since = EXCLUDED.breach_since,
                   breach_cnt   = EXCLUDED.breach_cnt,
                   next_eval_at = EXCLUDED.next_eval_at,
                   upd_date     = now()
        """.trimIndent()
        jdbc.update(
            sql,
            MapSqlParameterSource()
                .addValue("condId", condId)
                .addValue("scopeKey", scopeKey)
                .addValue("state", state.name)
                .addValue("value", value)
                .addValue("evalAt", evalAt)
                .addValue("breachSince", breachSince)
                .addValue("breachCnt", breachCnt)
                .addValue("nextEvalAt", nextEvalAt),
        )
    }

    /** 알림을 낸 순간 — 중복 억제 창의 기준점을 갱신한다 */
    fun markAlerted(condId: Int, scopeKey: String, alertId: Long, at: OffsetDateTime) {
        jdbc.update(
            """
            UPDATE ax.tb_alm_cond_state
               SET last_alert_id = :alertId, last_alert_at = :at, upd_date = now()
             WHERE cond_id = :condId AND scope_key = :scopeKey
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("alertId", alertId).addValue("at", at)
                .addValue("condId", condId).addValue("scopeKey", scopeKey),
        )
    }

    /** 억제 창에 걸려 보내지 않은 횟수. 억제 설정이 과한지 판단하는 값이다 */
    fun markSuppressed(condId: Int, scopeKey: String) {
        jdbc.update(
            """
            UPDATE ax.tb_alm_cond_state
               SET suppress_cnt = suppress_cnt + 1, upd_date = now()
             WHERE cond_id = :condId AND scope_key = :scopeKey
            """.trimIndent(),
            MapSqlParameterSource().addValue("condId", condId).addValue("scopeKey", scopeKey),
        )
    }

    /**
     * 조건이 지워졌거나 대상이 사라져 남은 상태를 정리한다.
     * 조건 삭제는 FK CASCADE 가 처리하므로, 여기서 보는 것은 '대상에서 빠진 설비' 뿐이다.
     */
    fun deleteMissingScopes(condId: Int, keep: Collection<String>): Int {
        if (keep.isEmpty()) return 0
        return jdbc.update(
            "DELETE FROM ax.tb_alm_cond_state WHERE cond_id = :condId AND NOT (scope_key = ANY (:keep))",
            MapSqlParameterSource().addValue("condId", condId).addValue("keep", keep.toTypedArray()),
        )
    }
}
