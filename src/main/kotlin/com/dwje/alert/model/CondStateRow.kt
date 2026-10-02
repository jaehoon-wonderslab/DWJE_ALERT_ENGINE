package com.dwje.alert.model

import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 조건 × 대상의 평가 상태 (ax.tb_alm_cond_state 한 행).
 *
 * 「10분 연속」·「30분 중복 억제」를 계산할 수 있는 유일한 근거다.
 * 이 행이 없으면 엔진은 매 틱마다 처음 보는 것처럼 판정한다.
 */
data class CondStateRow(
    val condId: Int,
    val scopeKey: String,
    val state: CondStateCd,
    val lastValue: BigDecimal?,
    val lastEvalAt: OffsetDateTime?,
    /** 연속 위반이 시작된 시각. 중간에 한 번이라도 정상이면 null 로 되돌린다 */
    val breachSince: OffsetDateTime?,
    val breachCnt: Int,
    val lastAlertId: Long?,
    /** 마지막으로 알림을 낸 시각 — 중복 억제 창의 기준점 */
    val lastAlertAt: OffsetDateTime?,
    val suppressCnt: Int,
    val nextEvalAt: OffsetDateTime,
) {
    companion object {
        /** 처음 보는 대상의 초기 상태 */
        fun initial(condId: Int, scopeKey: String, now: OffsetDateTime) = CondStateRow(
            condId = condId,
            scopeKey = scopeKey,
            state = CondStateCd.NORMAL,
            lastValue = null,
            lastEvalAt = null,
            breachSince = null,
            breachCnt = 0,
            lastAlertId = null,
            lastAlertAt = null,
            suppressCnt = 0,
            nextEvalAt = now,
        )
    }
}
