package com.dwje.alert.model

import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 수집한 지표 값 한 점. 그대로 ax.tb_met_metric_value 한 행이 된다.
 */
data class MetricPoint(
    val measuredAt: OffsetDateTime,
    val value: BigDecimal,
    val plantCd: String? = null,
    val wcCd: String? = null,
    val eqptCd: String? = null,
    val moldCd: String? = null,
    val itemCd: String? = null,
    val productId: Int? = null,
    /** 측정 원천 — 공통코드 MET_SRC */
    val srcCd: String = "BATCH",
    val remark: String? = null,
) {
    /** 이 점이 어느 대상의 값인지. 조건의 평가 단위에 맞춰 뽑는다 */
    fun scopeKey(dim: ScopeDim): String = when (dim) {
        ScopeDim.NONE -> "*"
        ScopeDim.EQPT -> eqptCd.orEmpty()
        ScopeDim.WC -> wcCd.orEmpty()
        ScopeDim.ITEM -> itemCd.orEmpty()
        ScopeDim.MOLD -> moldCd.orEmpty()
        ScopeDim.PRODUCT -> productId?.toString().orEmpty()
    }
}

/**
 * 지표 수집 정의 (ax.tb_met_metric_collect + ax.tb_met_metric_std).
 */
data class CollectDef(
    val metricId: Int,
    val metricCd: String,
    val metricNm: String,
    /** BUILTIN | SQL — 지금은 BUILTIN 만 쓴다 (V35 주석 참조) */
    val modeCd: String,
    /** BUILTIN 구현체 키. 비면 metricCd 를 쓴다 */
    val collectorCd: String,
    val dimCd: ScopeDim,
    val intervalSec: Int,
    val lookbackMin: Int,
    val sqlText: String?,
    val stdVal: BigDecimal,
    val warnVal: BigDecimal,
    val critVal: BigDecimal,
    val lastRunAt: OffsetDateTime?,
    val lastValueAt: OffsetDateTime?,
) {
    /**
     * 낮을수록 나쁜 지표인가.
     *
     * 가동률·수율은 낮을수록 나쁘고(주의 75 < 기준 85), 불량률은 높을수록 나쁘다.
     * 기준값과 주의값의 대소로 방향을 읽는다 — 지표마다 따로 설정할 필요가 없다.
     */
    val lowerIsWorse: Boolean get() = warnVal < stdVal

    /** 지표 판정 — 공통코드 MET_JUDGE. tb_met_metric_value.judge_cd 에 들어간다 */
    fun judge(v: BigDecimal): String = if (lowerIsWorse) {
        when {
            v < critVal -> "CRIT"
            v < warnVal -> "WARN"
            else -> "NORMAL"
        }
    } else {
        when {
            v > critVal -> "CRIT"
            v > warnVal -> "WARN"
            else -> "NORMAL"
        }
    }

    /** 이 정의가 지금 수집할 차례인가 */
    fun isDue(now: OffsetDateTime): Boolean =
        lastRunAt == null || !lastRunAt.plusSeconds(intervalSec.toLong()).isAfter(now)
}
