package com.dwje.alert.service.channel

import com.dwje.alert.config.AlertProperties
import com.dwje.alert.model.OutboundMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

/**
 * 메일 발송.
 *
 * `alert.message.mail-mode` 가 LOG 면 실제로 보내지 않고 내용을 로그로 남긴다.
 * 로컬에서 SMTP 서버 없이 판정·문구를 확인할 수 있어야 하기 때문이다.
 * 운영(prod 프로파일)은 SMTP 다 — LOG 모드로 운영에 뜨면 아무도 메일을 못 받는데
 * 로그에는 정상으로 보이므로, 기동 때 [com.dwje.alert.config.AlertConfigValidator] 가 경고한다.
 */
@Component
class MailChannel(
    private val props: AlertProperties,
    private val mailSenderProvider: ObjectProvider<JavaMailSender>,
) : AlertChannel {

    private val log = LoggerFactory.getLogger(javaClass)

    override val code = "MAIL"

    override fun send(message: OutboundMessage) {
        if (props.message.mailMode.uppercase() == "LOG") {
            log.info(
                "\n[메일 발송(로그 모드)] ───────────────────────────────\n" +
                    "  받는 사람 : {}\n  제목      : {}\n{}\n" +
                    "─────────────────────────────────────────────────",
                message.to, message.subject, message.body,
            )
            return
        }

        val sender = mailSenderProvider.getIfAvailable()
            ?: throw ChannelNotConfiguredException(
                "메일 모드가 SMTP 인데 메일 서버 설정(spring.mail.host)이 없습니다. " +
                    "PROD_MAIL_HOST 를 설정하거나 alert.message.mail-mode 를 LOG 로 두십시오.",
            )

        val mail = sender.createMimeMessage()
        MimeMessageHelper(mail, false, "UTF-8").apply {
            setFrom(props.message.fromAddress, props.message.fromName)
            setTo(message.to)
            setSubject(message.subject)
            setText(message.body, false)
        }
        sender.send(mail)
    }
}
