package com.dwje.alert.service.channel

import com.dwje.alert.model.OutboundMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 시스템 팝업.
 *
 * 보낼 곳이 따로 없다. 웹 화면(AL-01 이상 알림)이 ax.tb_alm_alert 를 읽어 띄우므로,
 * 알림 행이 만들어진 순간 이미 '전달' 된 것이다. 여기서는 발송 기록만 남긴다.
 *
 * 그래도 대기열을 거치게 두는 이유는, "이 사람에게 팝업으로 알렸다" 를
 * 메일·SMS 와 같은 자리(ax.tb_alm_send_log)에서 셀 수 있어야 하기 때문이다.
 */
@Component
class PopupChannel : AlertChannel {

    private val log = LoggerFactory.getLogger(javaClass)

    override val code = "POPUP"

    override fun send(message: OutboundMessage) {
        log.debug("팝업 알림 — 대상 {} / {}", message.userId ?: message.to, message.subject)
    }
}
