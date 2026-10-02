package com.dwje.alert.model

/**
 * 발송 대상 한 사람 × 한 채널.
 *
 * 수신 그룹 → 멤버 → 수신자 연락처 까지 풀어낸 결과다. 부재인 사람은 여기 오지 않는다.
 *
 * `isProxy` / `proxyOfUserId` 는 ax.tb_alm_send_log 에 있는 컬럼이라 그대로 두었지만
 * 지금은 항상 false · null 이다 — 2026-09-16 V36 에서 당번(대리 수신) 기능이 빠졌다.
 */
data class RecipientTarget(
    val groupId: Int,
    val userId: String,
    val userNm: String?,
    val deptId: Int?,
    val channelCd: String,
    val destAddr: String?,
    /** 이 사람이 야간에도 받는가. false 면 야간 구간에는 SKIPPED 로 남기고 보내지 않는다 */
    val nightRecv: Boolean,
    val isProxy: Boolean,
    val proxyOfUserId: String?,
    val groupWindowCd: String = "ALWAYS",
)

/** 대기열에서 집어온 발송 건 (ax.tb_alm_send_queue 한 행) */
data class QueuedSend(
    val queueId: Long,
    val alertId: Long,
    val groupId: Int?,
    val userId: String?,
    val channelCd: String,
    val destAddr: String?,
    val subject: String?,
    val body: String?,
    val escLevel: Short,
    val isProxy: Boolean,
    val proxyOfUserId: String?,
    val tryCnt: Int,
)

/** 채널 어댑터에 넘기는 발송 내용 */
data class OutboundMessage(
    val to: String,
    val subject: String,
    val body: String,
    val userId: String?,
)
