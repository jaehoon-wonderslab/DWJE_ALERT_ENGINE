package com.dwje.alert.service

import com.dwje.alert.common.Throwables
import com.dwje.alert.config.AlertProperties
import com.dwje.alert.model.OutboundMessage
import com.dwje.alert.model.SendResult
import com.dwje.alert.model.TickResult
import com.dwje.alert.repository.SendLogRepository
import com.dwje.alert.repository.SendQueueRepository
import com.dwje.alert.service.channel.AlertChannel
import com.dwje.alert.service.channel.ChannelNotConfiguredException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.time.OffsetDateTime

/**
 * ④ 발송 — 대기열을 집어가 채널로 보낸다.
 *
 * 대기열(작업 상태)과 발송 기록(시도 이력)을 나눠 쓴다. 재시도 루프가 기록을 UPDATE 하면
 * "3번째 시도에 나갔다" 가 덮여 사라지기 때문이다. 시도마다 기록 1행이 쌓인다.
 *
 * 여러 인스턴스가 동시에 돌아도 된다 — `FOR UPDATE SKIP LOCKED` 로 같은 행을 두 번 집지 않는다.
 * 판정(②③)은 advisory lock 으로 한 곳에서만 돌지만 발송은 나눠 가져도 문제가 없다.
 */
@Service
class SendDispatcher(
    private val queueRepo: SendQueueRepository,
    private val logRepo: SendLogRepository,
    private val props: AlertProperties,
    channels: List<AlertChannel>,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val byCode: Map<String, AlertChannel> = channels
        .groupBy { it.code }
        .mapValues { (code, list) ->
            require(list.size == 1) { "발송 채널이 중복 등록됐습니다: $code (${list.size}개)" }
            list.first()
        }

    private val workerId: String = runCatching { InetAddress.getLocalHost().hostName }
        .getOrDefault("unknown") + "/" + ProcessHandle.current().pid()

    init {
        log.info("발송 채널 {}종 — {}", byCode.size, byCode.keys.sorted().joinToString(", "))
    }

    fun dispatch(now: OffsetDateTime, result: TickResult) {
        if (!props.dispatch.enabled) return

        // 발송 도중 죽어 SENDING 으로 굳은 행을 되돌린다. try_cnt 는 이미 올라가 있어
        // 회수해도 무한 재시도가 되지 않는다.
        queueRepo.recoverStuck(props.dispatch.stuckAfterSec).let {
            if (it > 0) log.warn("발송 중 끊긴 {}건을 회수했습니다.", it)
        }

        val batch = queueRepo.claim(props.dispatch.batchSize, workerId)
        if (batch.isEmpty()) return

        batch.forEach { item ->
            val channel = byCode[item.channelCd]
            if (channel == null) {
                fail(item.queueId, item, "알 수 없는 발송 채널입니다: ${item.channelCd}", dead = true, result = result)
                return@forEach
            }

            try {
                channel.send(
                    OutboundMessage(
                        to = item.destAddr.orEmpty(),
                        subject = item.subject.orEmpty(),
                        body = item.body.orEmpty(),
                        userId = item.userId,
                    ),
                )
                queueRepo.markDone(item.queueId)
                logRepo.insert(
                    alertId = item.alertId, groupId = item.groupId, userId = item.userId,
                    channelCd = item.channelCd, destAddr = item.destAddr,
                    result = SendResult.SENT, escLevel = item.escLevel,
                    isProxy = item.isProxy, proxyOfUserId = item.proxyOfUserId,
                )
                result.sentCnt++
            } catch (e: ChannelNotConfiguredException) {
                // 재시도해도 소용없다. 바로 포기하고 이유를 남긴다.
                fail(item.queueId, item, e.message, dead = true, result = result)
            } catch (e: Exception) {
                val dead = item.tryCnt >= props.dispatch.maxTry
                fail(item.queueId, item, Throwables.describe(e), dead = dead, result = result)
            }
        }

        // 끝난 행을 정리한다. 기록은 tb_alm_send_log 에 영구 보존되므로 지워도 이력은 남는다.
        queueRepo.purgeDone(props.dispatch.doneRetentionDays)
    }

    private fun fail(
        queueId: Long,
        item: com.dwje.alert.model.QueuedSend,
        reason: String?,
        dead: Boolean,
        result: TickResult,
    ) {
        val nextTry = OffsetDateTime.now().plusSeconds(props.dispatch.backoffFor(item.tryCnt))
        queueRepo.markFailed(queueId, reason, nextTry, dead)
        logRepo.insert(
            alertId = item.alertId, groupId = item.groupId, userId = item.userId,
            channelCd = item.channelCd, destAddr = item.destAddr,
            result = SendResult.FAIL, failReason = reason, escLevel = item.escLevel,
            isProxy = item.isProxy, proxyOfUserId = item.proxyOfUserId,
        )
        result.failCnt++
        if (dead) {
            log.error("발송 포기 — 알림 {} / {} / {}", item.alertId, item.channelCd, reason)
        } else {
            log.warn(
                "발송 실패 — 알림 {} / {} / {} (시도 {}회, 다음 {})",
                item.alertId, item.channelCd, reason, item.tryCnt, nextTry,
            )
        }
    }

}
