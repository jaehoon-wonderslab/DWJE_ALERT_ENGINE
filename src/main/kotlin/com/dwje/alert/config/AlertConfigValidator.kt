package com.dwje.alert.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * 접속·발송 설정 검증 — 기동 즉시 수행한다.
 *
 * 접속 정보에 기본값을 두지 않는 이유는, 설정을 빠뜨린 채 엉뚱한 서버(localhost 등)에
 * 붙어 조용히 실패하는 상황을 막기 위해서다. 대신 비어 있으면 **무엇을 어떻게 설정해야
 * 하는지** 를 알려주고 기동을 중단한다.
 *
 * 값의 출처는 두 가지이며 아래가 위를 덮어쓴다.
 *   1. `config/local.yml`  — 로컬 개발 편의용 (선택, .gitignore 대상)
 *   2. 환경변수            — 운영 표준. systemd EnvironmentFile 로 주입한다
 */
@Component
class AlertConfigValidator(
    private val props: AlertProperties,
    private val environment: Environment? = null,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun validate() {
        val missing = buildList {
            if (props.db.url.isBlank()) add("ALERT_DB_URL")
            if (props.db.username.isBlank()) add("ALERT_DB_USERNAME")
            if (props.db.password.isBlank()) add("ALERT_DB_PASSWORD")
        }
        if (missing.isNotEmpty()) throw MissingConnectionSettingsException(missing)

        checkActiveProfiles()
        checkMailMode()

        log.info("접속 설정 확인 — {}", maskUrl(props.db.url))
        log.debug("계정 — {} (비밀번호 {}자)", props.db.username, props.db.password.length)
    }

    /**
     * 활성 프로파일 점검.
     *
     * 기동을 막지는 않는다 — 프로파일이 없어도 application.yml 값으로 정상 동작한다.
     * 대신 **소리 나게** 남긴다. 특히 오타(`produ`, `prd`)가 위험하다. Spring 은 없는
     * 프로파일을 조용히 무시하므로, 운영자는 prod 설정이 적용됐다고 믿는데 실제로는
     * 기본값(LOG 메일 모드 — 메일이 안 나간다)으로 돈다.
     */
    private fun checkActiveProfiles() {
        val active = environment?.activeProfiles?.toList() ?: return

        if (active.isEmpty()) {
            log.warn(
                "활성 프로파일이 없습니다 — application.yml 값으로만 동작합니다(메일 LOG 모드). " +
                    "실행 환경을 밝히십시오: --spring.profiles.active={}",
                KNOWN_PROFILES.joinToString("|"),
            )
            return
        }

        val unknown = active.filterNot { it in KNOWN_PROFILES }
        if (unknown.isNotEmpty()) {
            log.warn(
                "알 수 없는 프로파일 {} — 해당 설정 파일이 없어 무시되고 기본값으로 돕니다. " +
                    "오타가 아닌지 확인하십시오. 지정할 수 있는 값: {}",
                unknown, KNOWN_PROFILES.joinToString(" / "),
            )
        }
        log.info("활성 프로파일 — {}", active.joinToString(", "))
    }

    /**
     * 메일 모드 점검.
     *
     * SMTP 인데 `spring.mail.host` 가 비어 있으면 발송이 전부 실패한다.
     * 기동은 막지 않는다 — 팝업 채널만 쓰는 운영도 있다. 다만 반드시 알린다.
     */
    private fun checkMailMode() {
        val mode = props.message.mailMode.uppercase()
        if (mode !in setOf("LOG", "SMTP")) {
            throw IllegalStateException(
                "alert.message.mail-mode 는 LOG 또는 SMTP 여야 합니다. 받은 값: ${props.message.mailMode}",
            )
        }
        if (mode == "LOG") {
            log.warn("메일 모드 LOG — 실제 메일이 나가지 않고 발송 내용을 로그로만 남깁니다.")
            return
        }
        val host = environment?.getProperty("spring.mail.host").orEmpty()
        if (host.isBlank()) {
            log.error(
                "메일 모드가 SMTP 인데 spring.mail.host 가 비어 있습니다. " +
                    "메일 발송이 모두 실패로 기록됩니다. PROD_MAIL_HOST 를 설정하십시오.",
            )
        } else {
            log.info("메일 모드 SMTP — 발신 {} / 서버 {}", props.message.fromAddress, host)
        }
    }

    private fun maskUrl(url: String): String = url.replace(Regex("(?i)(password=)[^;&]*"), "$1****")

    /** 어떤 값을 어떻게 채워야 하는지 알려주는 예외 */
    class MissingConnectionSettingsException(missing: List<String>) : IllegalStateException(
        buildString {
            appendLine()
            appendLine("DB 접속 설정이 없습니다. 아래 환경변수를 지정하십시오.")
            appendLine()
            missing.forEach { appendLine("    $it") }
            appendLine()
            appendLine("설정 방법 (택 1)")
            appendLine("  ① 환경변수 — 운영 표준")
            appendLine("       export ALERT_DB_URL='jdbc:postgresql://<호스트>:5432/dwjedb'")
            appendLine("       export ALERT_DB_USERNAME='<계정>'")
            appendLine("       export ALERT_DB_PASSWORD='<비밀번호>'")
            appendLine()
            appendLine("  ② env 파일 — 로컬 개발 편의 (start.sh 가 자동으로 읽는다)")
            appendLine("       cp config/engine.env.example config/engine.env   # 값 채운 뒤")
            appendLine("       ./start.sh --profile=local")
            appendLine()
            appendLine("  ③ config/local.yml — 파일로 두고 싶을 때 (.gitignore 대상)")
            appendLine("       cp config/local.yml.example config/local.yml")
            appendLine()
            appendLine("  ④ systemd — 운영 서버")
            appendLine("       /etc/default/alert-engine 의 EnvironmentFile")
            appendLine()
            append("접속 정보는 소스 코드에 두지 마십시오. 저장소에 그대로 남습니다.")
        },
    )

    companion object {
        /** application-<이름>.yml 이 실제로 있는 프로파일 */
        val KNOWN_PROFILES = listOf("local", "test", "prod")
    }
}
