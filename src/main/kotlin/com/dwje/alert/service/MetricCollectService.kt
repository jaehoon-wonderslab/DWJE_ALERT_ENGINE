package com.dwje.alert.service

import com.dwje.alert.common.Throwables
import com.dwje.alert.config.AlertProperties
import com.dwje.alert.model.TickResult
import com.dwje.alert.repository.MetricRepository
import com.dwje.alert.service.collector.MetricCollector
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * ① 수집 — 원천에서 지표 값을 계산해 ax.tb_met_metric_value 에 쌓는다.
 *
 * ax.tb_met_metric_collect 에 use_flg='Y' 로 등록된 지표만 본다. 등록은 돼 있는데
 * 구현체가 없으면 그 사실을 남기고 넘어간다 — 조용히 건너뛰면 "왜 값이 안 쌓이지" 를
 * 로그 어디서도 알 수 없다.
 */
@Service
class MetricCollectService(
    private val metricRepo: MetricRepository,
    private val props: AlertProperties,
    collectors: List<MetricCollector>,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** metric_cd → 구현체. 같은 코드가 둘이면 기동 때 바로 알 수 있게 예외를 낸다 */
    private val byCode: Map<String, MetricCollector> = collectors
        .groupBy { it.metricCd }
        .mapValues { (code, list) ->
            require(list.size == 1) { "지표 수집기가 중복 등록됐습니다: $code (${list.size}개)" }
            list.first()
        }

    init {
        log.info("지표 수집기 {}종 — {}", byCode.size, byCode.keys.sorted().joinToString(", "))
    }

    fun collect(now: OffsetDateTime, result: TickResult, persist: Boolean = true) {
        if (!props.collect.enabled) return

        val defs = metricRepo.findEnabledCollectDefs()
        if (defs.isEmpty()) {
            warnOnce("수집이 켜진 지표가 없습니다 (ax.tb_met_metric_collect.use_flg='Y' 없음). 판정할 값이 쌓이지 않습니다.")
            return
        }

        defs.filter { !persist || it.isDue(now) }.forEach { def ->
            val collector = byCode[def.collectorCd]
            if (collector == null) {
                if (persist) metricRepo.markCollected(def.metricId, now, null, "수집기 구현체가 없습니다: ${def.collectorCd}")
                warnOnce("지표 '${def.metricCd}' 의 수집기 구현체(${def.collectorCd})가 없습니다. 값이 쌓이지 않습니다.")
                return@forEach
            }
            if (def.modeCd != "BUILTIN") {
                if (persist) metricRepo.markCollected(def.metricId, now, null, "아직 지원하지 않는 수집 방식: ${def.modeCd}")
                warnOnce("지표 '${def.metricCd}' 의 수집 방식 ${def.modeCd} 는 아직 지원하지 않습니다 (BUILTIN 만 가능).")
                return@forEach
            }

            // 수집 구간은 '직전 수집 이후'다. 엔진이 멈췄다 떠도 빈 구간이 생기지 않는다.
            // 다만 lookback 보다 더 거슬러 올라가지는 않는다 — 며칠 멈춘 뒤 재기동했을 때
            // 그 며칠을 한 점으로 뭉쳐 적재하면 그 값은 어느 시점의 값도 아니게 된다.
            val from = maxOf(
                def.lastRunAt ?: now.minusMinutes(def.lookbackMin.toLong()),
                now.minusMinutes(def.lookbackMin.toLong()),
            )
            if (!from.isBefore(now)) return@forEach

            runCatching { collector.collect(def, from, now) }
                .onSuccess { points ->
                    if (!persist) {
                        points.forEach { log.info("[DRY-RUN] 지표 수집 — {} = {}", def.metricCd, it.value) }
                        return@onSuccess
                    }
                    val inserted = metricRepo.insertPoints(def, points)
                    if (persist) metricRepo.markCollected(def.metricId, now, if (points.isEmpty()) null else now, null)
                    result.collectedCnt += inserted
                    if (inserted > 0) {
                        log.debug("지표 수집 — {} {}건 ({} ~ {})", def.metricCd, inserted, from, now)
                    }
                }
                .onFailure { e ->
                    if (persist) metricRepo.markCollected(def.metricId, now, null, Throwables.describe(e))
                    result.errors += "지표 수집 실패 ${def.metricCd}: ${Throwables.describe(e)}"
                    log.error("지표 수집에 실패했습니다 — {} ({})", def.metricCd, e.message)
                }
        }
    }

    @Volatile
    private var lastWarning: String? = null

    /**
     * 같은 사유가 반복될 때 로그를 한 번만 남긴다.
     * 1분 주기에서 같은 경고를 매번 찍으면 하루 1,440줄이 되어 정작 볼 로그가 묻힌다.
     */
    private fun warnOnce(message: String) {
        if (message == lastWarning) return
        lastWarning = message
        log.warn(message)
    }
}
