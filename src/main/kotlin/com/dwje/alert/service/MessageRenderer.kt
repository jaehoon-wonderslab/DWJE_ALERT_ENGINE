package com.dwje.alert.service

import com.dwje.alert.config.AlertProperties
import com.dwje.alert.model.AlertCondition
import com.dwje.alert.model.RecipientTarget
import com.dwje.alert.repository.CodeRepository
import com.dwje.alert.repository.RecipientRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 알림 문구를 만든다.
 *
 * 틀은 조건이 들고 있다 (ax.tb_alm_cond.msg_template). 엔진은 치환만 한다 —
 * 문구를 바꾸려고 엔진을 다시 배포하는 일이 없도록.
 *
 * [마스킹은 사람마다 다르다]
 * 조건에 blind_field_key 가 걸려 있으면 **수신자의 부서 데이터 권한** 을 확인해 값을 가린다.
 * 메일은 화면과 달리 권한 검사를 통과해서 나가는 경로가 아니다. 여기서 안 가리면
 * 데이터 접근 권한 설계(SY-03)가 메일로 그대로 샌다. 그래서 같은 알림이라도
 * 받는 사람에 따라 본문이 다를 수 있다.
 */
@Service
class MessageRenderer(
    private val codeRepo: CodeRepository,
    private val recipientRepo: RecipientRepository,
    private val props: AlertProperties,
) {

    data class Rendered(val subject: String, val body: String)

    data class Context(
        val cond: AlertCondition,
        val alertId: Long,
        val scopeKey: String,
        val value: BigDecimal,
        val occurredAt: OffsetDateTime,
        val evidence: String,
        val escLevel: Short = 0,
        val escLabel: String? = null,
    )

    fun render(ctx: Context, to: RecipientTarget?): Rendered {
        val cond = ctx.cond
        val masked = shouldMask(cond, to)

        val severityNm = codeRepo.labelOf("ALM_SEVERITY", cond.severity)
        val opNm = codeRepo.operatorOf(cond.opCd) ?: cond.opCd
        // 단위는 코드값이 아니라 표기값을 쓴다. 'PCT' 를 그대로 붙이면 "0PCT" 가 되어
        // 메일을 받은 사람이 읽다 멈춘다. MET_UNIT 의 code_nm 이 '%' 를 준다.
        val unit = codeRepo.labelOf("MET_UNIT", cond.unitCd ?: cond.thresholdUnit)
        val scopeText = if (ctx.scopeKey == "*") cond.targetDesc else ctx.scopeKey

        val vars = mapOf(
            "condNm" to cond.name,
            "metricNm" to (cond.metricNm ?: cond.metricDesc),
            "metricDesc" to cond.metricDesc,
            "value" to if (masked) MASK else ctx.value.stripTrailingZeros().toPlainString(),
            "unit" to unit,
            "op" to opNm,
            "threshold" to if (masked) MASK else (cond.threshold?.stripTrailingZeros()?.toPlainString() ?: cond.thresholdText),
            "scope" to scopeText,
            "eqptNm" to scopeText,
            "target" to cond.targetDesc,
            "severity" to severityNm,
            "evidence" to if (masked) "권한이 없어 근거 값을 가렸습니다" else ctx.evidence,
            "occurredAt" to ctx.occurredAt.format(TS),
            "link" to "${props.message.webBaseUrl.trimEnd('/')}/alert/list?alertId=${ctx.alertId}",
        )

        val prefix = buildString {
            append(props.message.subjectPrefix)
            append("[").append(severityNm).append("]")
            if (ctx.escLevel > 0) append("[${ctx.escLabel ?: "승격 ${ctx.escLevel}단계"}]")
        }
        val subject = "$prefix ${cond.name} — $scopeText"

        val body = buildString {
            appendLine(substitute(cond.msgTemplate, vars))
            appendLine()
            appendLine("─────────────────────────────────")
            appendLine("조건      ${cond.name}")
            appendLine("대상      $scopeText")
            appendLine("지표      ${vars["metricNm"]}")
            appendLine("측정값    ${vars["value"]}${unit.ifBlank { "" }}  (기준 $opNm ${vars["threshold"]}${unit.ifBlank { "" }})")
            appendLine("근거      ${vars["evidence"]}")
            appendLine("발생시각  ${vars["occurredAt"]}")
            appendLine("심각도    $severityNm")
            appendLine()
            appendLine("알림 상세 ${vars["link"]}")
            if (masked) {
                appendLine()
                appendLine("* 일부 값은 데이터 접근 권한이 없어 가려졌습니다. 필요하면 전산팀에 문의하십시오.")
            }
        }
        return Rendered(subject.take(300), body)
    }

    /**
     * 틀 치환. 정의하지 않은 변수는 **그대로 둔다.**
     *
     * 빈 문자열로 바꾸면 틀의 오타(`{{valu}}`)가 조용히 사라져, 값이 빠진 것을
     * 아무도 눈치채지 못한다. 그대로 남으면 메일을 받은 사람이 바로 알아본다.
     */
    private fun substitute(template: String, vars: Map<String, String>): String =
        VAR_PATTERN.replace(template) { m ->
            vars[m.groupValues[1].trim()] ?: m.value
        }

    private fun shouldMask(cond: AlertCondition, to: RecipientTarget?): Boolean {
        val key = cond.blindFieldKey ?: return false
        if (!props.message.maskByRecipient) return false
        // 받는 사람을 모르면(테스트 렌더링 등) 가리는 쪽을 고른다.
        val userId = to?.userId ?: return true
        return !recipientRepo.isFieldAllowed(userId, key)
    }

    companion object {
        private const val MASK = "***"
        private val VAR_PATTERN = Regex("""\{\{\s*([A-Za-z0-9_]+)\s*}}""")
        private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
