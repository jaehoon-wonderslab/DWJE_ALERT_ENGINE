package com.dwje.alert.repository

import com.dwje.alert.model.SendResult
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 발송 기록 (ax.tb_alm_send_log).
 *
 * 시도마다 1행이다. 성공만 남기지 않는다 — 실패·중복 억제(SUPPRESSED)·시간대 제외(SKIPPED)
 * 까지 남겨야 "왜 안 왔는가" 에 답할 수 있다. 공통코드 ALM_SEND_RESULT 가 처음부터
 * 그 네 가지를 갖고 있던 것은 그런 뜻이다.
 */
@Repository
class SendLogRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun insert(
        alertId: Long,
        groupId: Int?,
        userId: String?,
        channelCd: String,
        destAddr: String?,
        result: SendResult,
        failReason: String? = null,
        escLevel: Short = 0,
        isProxy: Boolean = false,
        proxyOfUserId: String? = null,
    ) {
        val sql = """
            INSERT INTO ax.tb_alm_send_log
                   (alert_id, group_id, user_id, channel_cd, dest_addr,
                    send_result_cd, fail_reason, esc_level, is_proxy, proxy_of_user_id)
            VALUES (:alertId, :groupId, :userId, :channelCd, :destAddr,
                    :result, :failReason, :escLevel, :isProxy, :proxyOf)
        """.trimIndent()
        jdbc.update(
            sql,
            MapSqlParameterSource()
                .addValue("alertId", alertId)
                .addValue("groupId", groupId)
                .addValue("userId", userId)
                .addValue("channelCd", channelCd)
                .addValue("destAddr", destAddr?.take(200))
                .addValue("result", result.name)
                .addValue("failReason", failReason?.take(300))
                .addValue("escLevel", escLevel.toInt())
                .addValue("isProxy", isProxy)
                .addValue("proxyOf", proxyOfUserId),
        )
    }
}
