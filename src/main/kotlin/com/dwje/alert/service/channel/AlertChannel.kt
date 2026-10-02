package com.dwje.alert.service.channel

import com.dwje.alert.model.OutboundMessage

/**
 * 발송 채널.
 *
 * 채널을 늘리는 일이 엔진 본체를 건드리지 않도록 인터페이스 하나로 끊었다.
 * 구현체를 빈으로 올리면 [com.dwje.alert.service.SendDispatcher] 가 코드로 찾아 쓴다.
 */
interface AlertChannel {

    /** 공통코드 ALM_CHANNEL 의 코드값 (MAIL · POPUP · SMS · MSG) */
    val code: String

    /**
     * 한 건을 보낸다. 실패는 예외로 알린다 — 조용히 실패하면 발송 기록이 거짓이 된다.
     *
     * @throws ChannelNotConfiguredException 연동이 준비되지 않은 채널. 재시도해도 소용없다
     */
    fun send(message: OutboundMessage)
}

/**
 * 연동처가 아직 없는 채널.
 *
 * 재시도로 해결되지 않으므로 대기열에서 바로 DEAD 로 보낸다.
 * **성공으로 처리하지 않는다** — SMS 가 나간 줄 알았는데 안 나가는 것이 가장 나쁘다.
 */
class ChannelNotConfiguredException(message: String) : RuntimeException(message)
