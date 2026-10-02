package com.dwje.alert

import com.dwje.alert.config.AlertProperties
import com.dwje.alert.model.OutboundMessage
import com.dwje.alert.service.channel.MailChannel
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.mail.javamail.JavaMailSender
import java.util.Properties
import kotlin.test.assertEquals

class MailChannelTest {
    @Test
    fun `한글 발신명 제목 본문을 UTF8 MIME 메일로 전송합니다`() {
        val sender = mock(JavaMailSender::class.java)
        val mime = MimeMessage(Session.getInstance(Properties()))
        `when`(sender.createMimeMessage()).thenReturn(mime)
        val provider = StaticListableBeanFactory(mapOf("sender" to sender)).getBeanProvider(JavaMailSender::class.java)
        val props = AlertProperties(message = AlertProperties.Message(mailMode = "SMTP", fromAddress = "dw_ai@derkwoo.com"))
        MailChannel(props, provider).send(OutboundMessage("jaehoon.lee@wonderslab.kr", "알림 시험", "한글 본문입니다.", null))
        verify(sender).send(mime)
        assertEquals("덕우전자 AX", (mime.from.single() as InternetAddress).personal)
        assertEquals("dw_ai@derkwoo.com", (mime.from.single() as InternetAddress).address)
        assertEquals("알림 시험", mime.subject)
        assertEquals("한글 본문입니다.", mime.content)
        assertEquals("jaehoon.lee@wonderslab.kr", (mime.allRecipients.single() as InternetAddress).address)
    }

    @Test
    fun `LOG 모드는 SMTP 발송을 호출하지 않습니다`() {
        val sender = mock(JavaMailSender::class.java)
        val provider = StaticListableBeanFactory(mapOf("sender" to sender)).getBeanProvider(JavaMailSender::class.java)
        MailChannel(AlertProperties(), provider).send(OutboundMessage("jaehoon.lee@wonderslab.kr", "시험", "시험", null))
        verifyNoInteractions(sender)
    }
}
