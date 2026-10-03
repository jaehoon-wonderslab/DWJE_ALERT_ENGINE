package com.dwje.alert.model

import java.math.BigDecimal

/**
 * 판정할 발송 조건 한 건 (ax.tb_alm_cond + 채널·수신그룹·개별대상).
 *
 * SY-04 화면이 등록한 그대로이며 엔진은 이 값을 해석만 한다.
 * 조건을 바꾸는 주체는 언제나 화면이다 — 엔진이 조건을 고치지 않는다.
 */
data class AlertCondition(
    val condId: Int,
    val name: String,
    val severity: String,
    val metricId: Int?,
    val metricCd: String?,
    val metricNm: String?,
    val metricDesc: String,
    val unitCd: String?,
    /** 비교 연산 코드 (ALM_OP). 실제 연산자는 attr1 에서 읽는다 */
    val opCd: String,
    val threshold: BigDecimal?,
    val thresholdText: String,
    val thresholdUnit: String?,
    val durationCd: String,
    val targetScopeCd: String,
    val targetDesc: String,
    val windowCd: String,
    val dedupCd: String,
    /** 본문에서 가려야 할 데이터 접근 항목. 없으면 가리지 않는다 */
    val blindFieldKey: String?,
    val channels: List<String>,
    val groupIds: List<Int>,
    /** targetScopeCd='PICK' 일 때 고른 대상 코드들 */
    val pickTargets: List<String>,
) {
    /** 지표 없이 등록된 조건은 판정할 값이 없다 — 화면에서 지표를 고르지 않은 경우다 */
    val isEvaluable: Boolean get() = metricId != null && threshold != null
}
