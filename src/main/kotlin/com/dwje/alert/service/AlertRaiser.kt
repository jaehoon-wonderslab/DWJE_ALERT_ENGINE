package com.dwje.alert.service

import com.dwje.alert.model.AlertCondition
import com.dwje.alert.model.RecipientTarget
import com.dwje.alert.model.ScopeDim
import com.dwje.alert.model.SendResult
import com.dwje.alert.model.TickResult
import com.dwje.alert.repository.AlertRepository
import com.dwje.alert.repository.CodeRepository
import com.dwje.alert.repository.CondStateRepository
import com.dwje.alert.repository.RecipientRepository
import com.dwje.alert.repository.SendLogRepository
import com.dwje.alert.repository.SendQueueRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * ③ 발생 — 판정된 위반을 알림으로 만들고 발송 대기열에 넣는다.
 *
 * 판정 순서는 **① 중복 억제 → ② 알림 생성 → ③ 유효 시간대** 이다.
 * 사람별 야간 미수신·부재 제외는 2026-10-03 에 없어졌다 — 시간 제한은 유효 시간대뿐이다.
 *
 * 시간대 밖이라고 알림 자체를 버리지 않는다. 새벽에 난 이상이 아침에 아무 흔적도 없으면
 * 안 되기 때문이다. 보내지 않은 이유는 ax.tb_alm_send_log 에 SKIPPED 로 남긴다 —
 * 공통코드 ALM_SEND_RESULT 가 처음부터 SUPPRESSED·SKIPPED 를 갖고 있던 것은 그런 뜻이다.
 */
@Service
class AlertRaiser(
    private val alertRepo: AlertRepository,
    private val stateRepo: CondStateRepository,
    private val queueRepo: SendQueueRepository,
    private val logRepo: SendLogRepository,
    private val recipientRepo: RecipientRepository,
    private val codeRepo: CodeRepository,
    private val renderer: MessageRenderer,
    private val groupWindow: GroupReceiveWindow,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun raise(breach: ConditionEvaluator.Breach, now: OffsetDateTime, result: TickResult) {
        val cond = breach.cond
        val dedupKey = "${cond.condId}|${breach.scopeKey}"

        // ① 중복 억제 — 창 안이면 기존 알림에 재발만 기록한다.
        //    알림을 아예 안 만들면 "30분 동안 12번 터진" 사실이 사라진다.
        val suppressedOf = findSuppressor(cond, dedupKey, now)
        if (suppressedOf != null) {
            alertRepo.bumpHit(suppressedOf, now, breach.value)
            stateRepo.markSuppressed(cond.condId, breach.scopeKey)
            logRepo.insert(
                alertId = suppressedOf,
                groupId = null,
                userId = null,
                channelCd = cond.channels.firstOrNull() ?: "MAIL",
                destAddr = null,
                result = SendResult.SUPPRESSED,
                failReason = "중복 억제 (${codeRepo.labelOf("ALM_DEDUP", cond.dedupCd)})",
            )
            result.suppressCnt++
            log.debug("중복 억제 — 조건 {} 대상 {} (알림 {})", cond.name, breach.scopeKey, suppressedOf)
            return
        }

        // ② 알림 생성
        val alertId = alertRepo.insert(
            condId = cond.condId,
            metricId = cond.metricId,
            severity = cond.severity,
            title = titleOf(cond, breach),
            occurredAt = now,
            metricValue = breach.value,
            threshold = cond.threshold,
            evidence = breach.evidence,
            scopeKey = breach.scopeKey,
            dedupKey = dedupKey,
            targetDesc = if (breach.scopeKey == "*") cond.targetDesc else breach.scopeKey,
            plantCd = breach.plantCd,
            wcCd = breach.wcCd,
            eqptCd = breach.scopeKey.takeIf { breach.dim == ScopeDim.EQPT },
            itemCd = breach.scopeKey.takeIf { breach.dim == ScopeDim.ITEM },
            moldCd = breach.scopeKey.takeIf { breach.dim == ScopeDim.MOLD },
        )
        stateRepo.markAlerted(cond.condId, breach.scopeKey, alertId, now)
        result.raiseCnt++
        log.info("알림 발생 — [{}] {} / 대상 {} / {}", cond.severity, cond.name, breach.scopeKey, breach.evidence)

        // ③ 유효 시간대 — 밖이면 알림은 남기고 발송만 건너뛴다
        if (!isInWindow(cond, now)) {
            logRepo.insert(
                alertId = alertId, groupId = null, userId = null,
                channelCd = cond.channels.firstOrNull() ?: "MAIL", destAddr = null,
                result = SendResult.SKIPPED,
                failReason = "유효 시간대 밖 (${codeRepo.labelOf("ALM_WINDOW", cond.windowCd)})",
            )
            result.skipCnt++
            return
        }

        enqueueFor(alertId, cond, breach, now, result)
    }

    /** 알림 한 건을 수신 그룹의 사람들에게 펼쳐 대기열에 넣는다 */
    private fun enqueueFor(
        alertId: Long,
        cond: AlertCondition,
        breach: ConditionEvaluator.Breach,
        now: OffsetDateTime,
        result: TickResult,
    ) {
        val targets = recipientRepo.findTargets(cond.groupIds, cond.channels)
        if (targets.isEmpty()) {
            // 조용히 넘기지 않는다. "등록은 했는데 아무도 안 받는" 상태를 화면에서 볼 수 있어야 한다.
            logRepo.insert(
                alertId = alertId, groupId = cond.groupIds.firstOrNull(), userId = null,
                channelCd = cond.channels.firstOrNull() ?: "MAIL", destAddr = null,
                result = SendResult.FAIL,
                failReason = "수신 가능한 멤버가 없습니다(계정 정지 포함)",
            )
            result.failCnt++
            log.warn("보낼 수신자가 없습니다 — 조건 '{}' (수신 그룹 {})", cond.name, cond.groupIds)
            return
        }

        val ctx = MessageRenderer.Context(
            cond = cond, alertId = alertId, scopeKey = breach.scopeKey,
            value = breach.value, occurredAt = now, evidence = breach.evidence,
        )

        val rows = mutableListOf<SendQueueRepository.NewSend>()
        targets.forEach { t ->
            val windowReason = groupWindow.skipReason(t.groupWindowCd, now)
            if (windowReason != null) {
                logRepo.insert(
                    alertId = alertId, groupId = t.groupId, userId = t.userId,
                    channelCd = t.channelCd, destAddr = t.destAddr,
                    result = SendResult.SKIPPED, failReason = windowReason,
                )
                result.skipCnt++
                return@forEach
            }
            if (t.destAddr.isNullOrBlank()) {
                logRepo.insert(
                    alertId = alertId, groupId = t.groupId, userId = t.userId,
                    channelCd = t.channelCd, destAddr = null,
                    result = SendResult.FAIL,
                    failReason = "${codeRepo.labelOf("ALM_CHANNEL", t.channelCd)} 연락처가 비어 있습니다",
                    isProxy = t.isProxy, proxyOfUserId = t.proxyOfUserId,
                )
                result.failCnt++
                return@forEach
            }

            val msg = renderer.render(ctx, t)
            rows += SendQueueRepository.NewSend(
                alertId = alertId,
                groupId = t.groupId,
                userId = t.userId,
                channelCd = t.channelCd,
                destAddr = t.destAddr,
                subject = msg.subject,
                body = msg.body,
                isProxy = t.isProxy,
                proxyOfUserId = t.proxyOfUserId,
            )
        }

        result.queuedCnt += queueRepo.enqueue(rows)
    }

    /** 억제 창 안에 이미 낸 알림. 없으면 null */
    private fun findSuppressor(cond: AlertCondition, dedupKey: String, now: OffsetDateTime): Long? {
        val minutes = codeRepo.dedupMinutes(cond.dedupCd)
        return when {
            minutes == 0 -> null                                        // 억제 없음
            minutes < 0 -> alertRepo.findLiveByDedupToday(dedupKey)      // 달력일 1회
            else -> alertRepo.findLiveByDedup(dedupKey, now.minusMinutes(minutes.toLong()))
        }
    }

    /**
     * 지금이 조건의 유효 시간대 안인가.
     *
     * 시간대 코드의 attr1/attr2 가 비어 있으면 **보낸다.** 설정이 덜 된 것 때문에
     * 알림을 삼키는 쪽이 더 나쁘기 때문이다.
     */
    private fun isInWindow(cond: AlertCondition, now: OffsetDateTime): Boolean {
        val time = now.toLocalTime()

        // 폐지된 시간대가 남은 조건은 발송하지 않습니다. DB에서도 조건을 중지합니다.
        if (cond.windowCd == "ONCE") return false
        if (cond.windowCd == "WORKDAY") {
            val day = now.dayOfWeek.value
            if (day >= 6) return false                                  // 토·일 제외
        }
        val range = codeRepo.windowRange(cond.windowCd) ?: return true
        return !time.isBefore(range.first) && !time.isAfter(range.second)
    }

    private fun titleOf(cond: AlertCondition, breach: ConditionEvaluator.Breach): String {
        val scope = if (breach.scopeKey == "*") cond.targetDesc else breach.scopeKey
        val unit = codeRepo.labelOf("MET_UNIT", cond.unitCd ?: cond.thresholdUnit)
        return "$scope ${cond.metricNm ?: cond.metricDesc} ${breach.value.stripTrailingZeros().toPlainString()}$unit"
    }
}
