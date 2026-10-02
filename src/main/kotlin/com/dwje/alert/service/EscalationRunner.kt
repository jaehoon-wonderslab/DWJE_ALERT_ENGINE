package com.dwje.alert.service

import com.dwje.alert.config.AlertProperties
import com.dwje.alert.model.SendResult
import com.dwje.alert.model.TickResult
import com.dwje.alert.repository.AlertRepository
import com.dwje.alert.repository.ConditionRepository
import com.dwje.alert.repository.RecipientRepository
import com.dwje.alert.repository.SendLogRepository
import com.dwje.alert.repository.SendQueueRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * ⑤ 승격 — 확인되지 않은 알림을 상위 그룹으로 올린다.
 *
 * 규칙은 ax.tb_alm_escalation_rule(after_min · to_group_id)이 쥐고 있고, 조건별 적용 여부는
 * ax.tb_alm_cond_escalation.is_on 이 정한다. 엔진은 시간이 지났는지만 본다.
 *
 * 같은 단계로 두 번 보내지 않는 것은 대기열의 유니크 키(alert_id·수신자·채널·esc_level)가
 * 보장한다 — 승격 처리 도중 엔진이 죽어 다시 돌아도 중복 발송이 없다.
 */
@Service
class EscalationRunner(
    private val alertRepo: AlertRepository,
    private val condRepo: ConditionRepository,
    private val recipientRepo: RecipientRepository,
    private val queueRepo: SendQueueRepository,
    private val logRepo: SendLogRepository,
    private val renderer: MessageRenderer,
    private val props: AlertProperties,
    private val groupWindow: GroupReceiveWindow,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun run(now: OffsetDateTime, result: TickResult) {
        if (!props.escalation.enabled) return

        val targets = alertRepo.findEscalationTargets(ESCALATE_PER_TICK)
        if (targets.isEmpty()) return

        targets.forEach { t ->
            val cond = t.condId?.let { condRepo.findOne(it) }
            if (cond == null) {
                log.warn("승격 대상 알림 {} 의 조건을 찾을 수 없습니다. 건너뜁니다.", t.alertId)
                return@forEach
            }
            if (t.toGroupId == null) {
                // 승격 대상 그룹이 비어 있으면 보낼 곳이 없다. 단계만 올리면 "올렸는데 아무도 못 받은"
                // 상태가 되므로, 단계를 올리지 않고 설정을 고치라고 남긴다.
                logRepo.insert(
                    alertId = t.alertId, groupId = null, userId = null,
                    channelCd = cond.channels.firstOrNull() ?: "MAIL", destAddr = null,
                    result = SendResult.FAIL, escLevel = t.escLevel,
                    failReason = "승격 규칙 '${t.levelNm}' 에 수신 그룹이 지정되지 않았습니다",
                )
                result.failCnt++
                return@forEach
            }

            val recipients = recipientRepo.findTargets(listOf(t.toGroupId), cond.channels)
            if (recipients.isEmpty()) {
                logRepo.insert(
                    alertId = t.alertId, groupId = t.toGroupId, userId = null,
                    channelCd = cond.channels.firstOrNull() ?: "MAIL", destAddr = null,
                    result = SendResult.FAIL, escLevel = t.escLevel,
                    failReason = "수신 가능한 멤버가 없습니다(부재·계정 정지 포함)",
                )
                result.failCnt++
                return@forEach
            }

            val ctx = MessageRenderer.Context(
                cond = cond,
                alertId = t.alertId,
                scopeKey = "*",
                value = BigDecimal.ZERO,
                occurredAt = now,
                evidence = "${t.afterMin}분이 지나도록 확인되지 않아 '${t.levelNm}' 단계로 올렸습니다",
                escLevel = t.escLevel,
                escLabel = t.levelNm,
            )

            val rows = recipients
                .filter { r ->
                    val reason = groupWindow.skipReason(r.groupWindowCd, cond.ignoreWindow, now)
                        ?: if (props.night.contains(now.toLocalTime()) && !r.nightRecv) "야간 미수신 설정" else null
                    if (reason != null) {
                        logRepo.insert(
                            alertId = t.alertId, groupId = r.groupId, userId = r.userId,
                            channelCd = r.channelCd, destAddr = r.destAddr,
                            result = SendResult.SKIPPED, escLevel = t.escLevel, failReason = reason,
                        )
                        result.skipCnt++
                    }
                    reason == null
                }
                .filter { !it.destAddr.isNullOrBlank() }
                .map { r ->
                    val msg = renderer.render(ctx, r)
                    SendQueueRepository.NewSend(
                        alertId = t.alertId, groupId = r.groupId, userId = r.userId,
                        channelCd = r.channelCd, destAddr = r.destAddr,
                        subject = msg.subject, body = msg.body,
                        escLevel = t.escLevel, isProxy = r.isProxy, proxyOfUserId = r.proxyOfUserId,
                    )
                }

            val queued = queueRepo.enqueue(rows)
            alertRepo.raiseEscLevel(t.alertId, t.escLevel)
            result.queuedCnt += queued
            result.escalatedCnt++
            log.warn(
                "알림 승격 — {} ({}분 미확인) → {} 단계 / 수신 {}명",
                t.title, t.afterMin, t.levelNm, queued,
            )
        }
    }

    companion object {
        /** 한 틱에서 올릴 상한. 밀린 것은 다음 틱에서 이어 처리한다 */
        private const val ESCALATE_PER_TICK = 100
    }
}
