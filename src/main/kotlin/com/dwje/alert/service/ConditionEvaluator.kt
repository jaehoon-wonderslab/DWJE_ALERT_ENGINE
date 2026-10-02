package com.dwje.alert.service

import com.dwje.alert.config.AlertProperties
import com.dwje.alert.model.AlertCondition
import com.dwje.alert.model.CondStateCd
import com.dwje.alert.model.CondStateRow
import com.dwje.alert.model.DurationKind
import com.dwje.alert.model.ScopeDim
import com.dwje.alert.model.TickResult
import com.dwje.alert.repository.AlertRepository
import com.dwje.alert.repository.CodeRepository
import com.dwje.alert.repository.CondStateRepository
import com.dwje.alert.repository.MetricRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * ② 평가 — 조건 × 대상을 판정하고 상태를 갱신한다.
 *
 * 여기서 하는 일은 세 가지다.
 *   · 임계 비교 (ALM_OP 의 attr1 이 연산자를 준다)
 *   · 지속 조건 판정 (연속 · 이동평균 · 일 마감)
 *   · 상태 전이 기록 (NORMAL ↔ PENDING ↔ BREACH)
 *
 * 알림을 만들지는 않는다. 그것은 [AlertRaiser] 의 일이다 —
 * "임계를 넘었다" 와 "알림을 보낸다" 는 다른 판단이기 때문이다(시간대·중복 억제).
 */
@Service
class ConditionEvaluator(
    private val metricRepo: MetricRepository,
    private val stateRepo: CondStateRepository,
    private val alertRepo: AlertRepository,
    private val codeRepo: CodeRepository,
    private val props: AlertProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 판정 결과 — 알림을 낼 차례가 된 대상 */
    data class Breach(
        val cond: AlertCondition,
        val scopeKey: String,
        /** 실제로 판정한 단위. 조건이 NONE 이어도 수집 단위를 따랐으면 그 단위다 */
        val dim: ScopeDim,
        val value: BigDecimal,
        val measuredAt: OffsetDateTime,
        val evidence: String,
        val plantCd: String?,
        val wcCd: String?,
    )

    /**
     * @param persist false 면 상태를 쓰지 않는다. `--dry-run` 이 진짜로 아무것도 바꾸지 않게 하려는 것이다.
     *                상태를 쓰면 다음 실제 판정의 연속 시간·억제 창이 달라져 "확인만 했다" 가 아니게 된다.
     */
    fun evaluate(
        cond: AlertCondition,
        now: OffsetDateTime,
        result: TickResult,
        persist: Boolean = true,
    ): List<Breach> {
        if (!cond.isEvaluable) {
            warnOnce(
                "cond-${cond.condId}-noMetric",
                "조건 '${cond.name}' 에 감지 지표나 임계값이 없어 판정할 수 없습니다. SY-04 화면에서 채우십시오.",
            )
            return emptyList()
        }
        val metricId = cond.metricId!!
        val threshold = cond.threshold!!

        // 수집이 멈췄는지 본다. 낡은 값으로 판정하면 이미 끝난 이상이 계속 나가거나
        // 진짜 이상을 정상으로 본다 — 둘 다 알림을 못 믿게 만든다.
        val lastValueAt = metricRepo.lastValueAt(metricId)
        if (lastValueAt == null) {
            warnOnce(
                "cond-${cond.condId}-noData",
                "조건 '${cond.name}' 의 지표(${cond.metricCd ?: metricId})에 쌓인 값이 없습니다. " +
                    "ax.tb_met_metric_collect 에서 수집을 켜십시오.",
            )
            return emptyList()
        }
        val staleLimitSec = cond.evalIntervalSec.toLong() * props.engine.staleFactor
        if (Duration.between(lastValueAt, now).seconds > maxOf(staleLimitSec, 600)) {
            warnOnce(
                "cond-${cond.condId}-stale",
                "조건 '${cond.name}' 의 지표 값이 ${Duration.between(lastValueAt, now).toMinutes()}분째 갱신되지 않아 " +
                    "판정하지 않습니다 (마지막 값 $lastValueAt). 수집이 멈췄는지 확인하십시오.",
            )
            return emptyList()
        }

        val kind = codeRepo.durationKind(cond.durationCd)
        val requiredSec = codeRepo.durationSeconds(cond.durationCd)

        // 일 마감 판정은 하루 한 번이다. 지금이 그 시각 전이면 아무것도 하지 않는다.
        if (kind == DurationKind.CLOSE && !isCloseTime(cond, now)) return emptyList()

        // 조회 구간 — 이동평균이면 그 구간, 연속 판정이면 최근 값만 있으면 되지만
        // 변화율(RATE) 비교를 위해 직전 값까지는 봐야 한다.
        val windowSec = maxOf(requiredSec, MIN_WINDOW_SEC)
        val dim = effectiveDim(cond, metricId)
        val readings = metricRepo.readValues(metricId, dim, now.minusSeconds(windowSec))
        if (readings.isEmpty()) return emptyList()

        val states = stateRepo.findByCond(cond.condId)
        val scopeKeys = resolveScopeKeys(cond, dim, readings.keys)
        val breaches = mutableListOf<Breach>()

        scopeKeys.take(props.engine.maxEvalPerTick).forEach { key ->
            val reading = readings[key] ?: return@forEach
            val prev = states[key] ?: CondStateRow.initial(cond.condId, key, now)

            // 조건별 평가 주기. 1분 틱보다 성기게 잡은 조건은 차례가 아닐 때 건너뛴다.
            if (prev.lastEvalAt != null && now.isBefore(prev.nextEvalAt)) return@forEach

            val compareValue = if (kind == DurationKind.AVG) reading.avgValue else reading.value
            val violated = compare(cond.opCd, compareValue, threshold, reading.prevValue)
            result.evalCnt++

            val nextEvalAt = nextEvalAt(cond, kind, now)

            if (!violated) {
                // 값이 돌아왔다 — 연속은 끊긴다. 중간에 한 번이라도 정상이면 처음부터 다시 센다.
                if (persist) {
                    if (prev.state == CondStateCd.BREACH && cond.autoClose && prev.lastAlertId != null) {
                        alertRepo.markResolved(prev.lastAlertId, now)
                    }
                    stateRepo.upsertEvaluation(
                        cond.condId, key, CondStateCd.NORMAL, compareValue, now, null, 0, nextEvalAt,
                    )
                }
                return@forEach
            }

            val breachSince = prev.breachSince ?: now
            val heldSec = Duration.between(breachSince, now).seconds
            // 연속(CONT)만 시간을 요구한다. 이동평균·일 마감은 구간 자체가 판정 근거다.
            val satisfied = kind != DurationKind.CONT || heldSec >= requiredSec
            val state = if (satisfied) CondStateCd.BREACH else CondStateCd.PENDING

            if (persist) {
                stateRepo.upsertEvaluation(
                    cond.condId, key, state, compareValue, now, breachSince, prev.breachCnt + 1, nextEvalAt,
                )
            }

            if (satisfied) {
                breaches += Breach(
                    cond = cond,
                    scopeKey = key,
                    dim = dim,
                    value = compareValue.setScale(4, RoundingMode.HALF_UP),
                    measuredAt = reading.measuredAt,
                    evidence = evidenceOf(cond, kind, compareValue, reading.sampleCnt, heldSec),
                    plantCd = reading.plantCd,
                    wcCd = reading.wcCd,
                )
            } else {
                log.debug(
                    "감시중 — 조건 {} 대상 {} 값 {} (연속 {}초 / 필요 {}초)",
                    cond.name, key, compareValue, heldSec, requiredSec,
                )
            }
        }
        return breaches
    }

    /**
     * 판정할 대상을 고른다.
     *
     * 값이 쌓인 대상만 본다 — 조건이 '전체 설비' 라도 값이 없는 설비는 판정할 수 없다.
     * `PICK`(개별 설비 선택)이면 고른 대상으로 좁힌다.
     */
    private fun resolveScopeKeys(cond: AlertCondition, dim: ScopeDim, available: Set<String>): List<String> = when {
        dim == ScopeDim.NONE -> listOf("*")
        cond.targetScopeCd == "PICK" && cond.pickTargets.isNotEmpty() ->
            available.filter { it in cond.pickTargets }.sorted()
        else -> available.sorted()
    }

    /**
     * 실제로 판정할 단위.
     *
     * 조건이 단위를 밝혔으면 그것을 쓴다. NONE 이면 지표가 쌓이는 단위(수집 정의 dim_cd)를 따른다 —
     * SY-04 화면이 평가 단위를 고르지 않아 조건은 늘 NONE 으로 들어오는데, 설비별로 쌓인 값을
     * 묶어 버리면 같은 시각의 수백 행 중 아무 설비 하나의 값으로 판정하게 된다.
     */
    private fun effectiveDim(cond: AlertCondition, metricId: Int): ScopeDim {
        if (cond.scopeDim != ScopeDim.NONE) return cond.scopeDim
        return metricRepo.collectDimOf(metricId) ?: ScopeDim.NONE
    }

    /**
     * 임계 비교. 연산자는 공통코드(ALM_OP.attr1)에서 온다.
     *
     * `RATE` 는 연산자가 아니라 **직전 값 대비 변화율(%)** 을 임계와 비교하는 판정이다.
     * 직전 값이 없으면(값이 하나뿐) 판정하지 않는다 — 변화율을 알 수 없는데
     * 0% 로 보면 "변화 없음" 이 되어 이상을 놓친다.
     */
    private fun compare(opCd: String, value: BigDecimal, threshold: BigDecimal, prev: BigDecimal?): Boolean {
        if (opCd == "RATE") {
            if (prev == null || prev.signum() == 0) return false
            val rate = value.subtract(prev)
                .divide(prev.abs(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
            return rate.abs() >= threshold.abs()
        }
        val op = codeRepo.operatorOf(opCd)
        if (op == null) {
            warnOnce("op-$opCd", "비교 연산자 코드 '$opCd' 의 attr1 이 비어 있습니다. V35 를 적용했는지 확인하십시오.")
            return false
        }
        val cmp = value.compareTo(threshold)
        return when (op) {
            ">=" -> cmp >= 0
            ">" -> cmp > 0
            "<=" -> cmp <= 0
            "<" -> cmp < 0
            "=" -> cmp == 0
            else -> {
                warnOnce("op-unknown-$op", "알 수 없는 비교 연산자입니다: $op (코드 $opCd)")
                false
            }
        }
    }

    /** 다음 평가 시각. 일 마감 조건은 내일 같은 시각으로 밀어 하루 한 번만 돌게 한다 */
    private fun nextEvalAt(cond: AlertCondition, kind: DurationKind, now: OffsetDateTime): OffsetDateTime =
        if (kind == DurationKind.CLOSE) {
            now.toLocalDate().plusDays(1).atTime(closeTime(cond)).atOffset(now.offset)
        } else {
            now.plusSeconds(cond.evalIntervalSec.toLong())
        }

    /**
     * 일 마감 판정 시각.
     *
     * 조건에 지정 시각(window_time)이 있으면 그것을, 없으면 08:00 을 쓴다.
     * 「마감」의 기준 시각이 아직 업무적으로 정해지지 않아 기본값을 둔 것이다
     * (설계서 §10-4 — 이관 야간 배치 완료 시점으로 맞출지 결정 대기).
     */
    private fun closeTime(cond: AlertCondition) = cond.windowTime ?: java.time.LocalTime.of(8, 0)

    private fun isCloseTime(cond: AlertCondition, now: OffsetDateTime): Boolean =
        !now.toLocalTime().isBefore(closeTime(cond))

    private fun evidenceOf(
        cond: AlertCondition,
        kind: DurationKind,
        raw: BigDecimal,
        sampleCnt: Int,
        heldSec: Long,
    ): String {
        // 0.000000 처럼 꼬리 0 이 붙은 채로 문구에 들어가면 읽기 나쁘다
        val value = raw.stripTrailingZeros().toPlainString()
        return when (kind) {
            DurationKind.AVG ->
                "최근 ${codeRepo.durationSeconds(cond.durationCd) / 60}분 이동평균 $value (표본 ${sampleCnt}건)"
            DurationKind.CLOSE ->
                "일 마감 집계 $value (표본 ${sampleCnt}건)"
            DurationKind.CONT ->
                if (heldSec >= 60) "${heldSec / 60}분 연속 임계 초과 (현재 $value)"
                else "임계 초과 (현재 $value)"
        }
    }

    // ── 로그 억제 ────────────────────────────────────────────────────────────────────
    //  1분 주기에서 같은 경고를 매번 찍으면 하루 1,440줄이 된다. 같은 사유는 1시간에 한 번만.
    private val warned = HashMap<String, Instant>()

    @Synchronized
    private fun warnOnce(key: String, message: String) {
        val last = warned[key]
        if (last != null && Duration.between(last, Instant.now()) < WARN_TTL) return
        warned[key] = Instant.now()
        log.warn(message)
    }

    companion object {
        /** 변화율 비교를 위해 최소한 이만큼은 거슬러 본다 */
        private const val MIN_WINDOW_SEC = 900L
        private val WARN_TTL: Duration = Duration.ofHours(1)
    }
}
