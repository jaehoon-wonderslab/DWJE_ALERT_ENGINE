package com.dwje.alert.service.channel

import com.dwje.alert.model.OutboundMessage
import org.springframework.stereotype.Component

/**
 * 아직 연동처가 없는 채널.
 *
 * 공통코드 ALM_CHANNEL 에는 SMS·메신저가 있고 SY-04 화면에서 고를 수 있다.
 * 그런데 실제 연동처(문자 발송 업체·사내 메신저 API)가 정해지지 않았다.
 *
 * **보낸 척하지 않는다.** 성공으로 기록하면 "SMS 로 알렸다" 는 기록만 남고
 * 아무도 받지 못한 상태가 되는데, 그것이 알림 시스템에서 가장 나쁜 실패다.
 * 대기열에서 곧바로 DEAD 로 떨어뜨리고 이유를 발송 기록에 남긴다.
 *
 * 연동이 정해지면 이 파일의 구현체를 실제 구현으로 갈아 끼우면 된다 —
 * 엔진 본체는 채널을 코드로만 알고 있어 다른 곳을 고칠 필요가 없다.
 */
@Component
class SmsChannel : AlertChannel {
    override val code = "SMS"
    override fun send(message: OutboundMessage): Unit =
        throw ChannelNotConfiguredException("SMS 연동처가 아직 없습니다. 조건의 발송 채널에서 SMS 를 빼거나 연동을 붙이십시오.")
}

@Component
class MessengerChannel : AlertChannel {
    override val code = "MSG"
    override fun send(message: OutboundMessage): Unit =
        throw ChannelNotConfiguredException("메신저 연동처가 아직 없습니다. 조건의 발송 채널에서 메신저를 빼거나 연동을 붙이십시오.")
}
