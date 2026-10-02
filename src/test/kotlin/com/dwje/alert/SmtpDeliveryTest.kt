package com.dwje.alert

import com.dwje.alert.config.AlertProperties
import com.dwje.alert.model.OutboundMessage
import com.dwje.alert.service.channel.MailChannel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

/** 명시적으로 실행할 때만 지정된 시험 수신자에게 메일 한 통을 보냅니다. DB는 사용하지 않습니다. */
@EnabledIfEnvironmentVariable(named = "ALERT_SMTP_DELIVERY_TEST", matches = "1")
class SmtpDeliveryTest {
    @Test
    fun `SMTP 채널로 시험 수신자에게 한 통 발송합니다`() {
        val config = Files.newBufferedReader(Path.of("config/local.yml")).use { Yaml().load<Map<String, Any>>(it) }
        @Suppress("UNCHECKED_CAST")
        val mail = (config["spring"] as Map<String, Any>)["mail"] as Map<String, Any>
        val sender = JavaMailSenderImpl().apply {
            host = mail["host"].toString()
            port = mail["port"].toString().toInt()
            username = mail["username"].toString()
            password = mail["password"].toString()
            defaultEncoding = "UTF-8"
            @Suppress("UNCHECKED_CAST")
            val settings = mail["properties"] as Map<String, Any>
            settings.forEach { (key, value) -> javaMailProperties.setProperty(key, value.toString()) }
        }
        val beans = StaticListableBeanFactory(mapOf("mailSender" to sender))
        val props = AlertProperties(message = AlertProperties.Message(mailMode = "SMTP", fromAddress = mail["username"].toString()))
        MailChannel(props, beans.getBeanProvider(JavaMailSender::class.java)).send(
            OutboundMessage(
                to = "jaehoon.lee@wonderslab.kr",
                subject = "[덕우전자 AX] Alert_Engine SMTP 발송 시험",
                body = "Alert_Engine 메일 채널의 SMTP 발송 시험입니다.\n발송 시각: ${OffsetDateTime.now()}\n실제 알림 조건이나 수신자 설정은 변경하지 않았습니다.",
                userId = null,
            ),
        )
        println("SMTP 서버가 시험 메일을 수락했습니다: jaehoon.lee@wonderslab.kr")
    }
}
