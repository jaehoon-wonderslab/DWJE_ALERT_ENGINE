package com.dwje.alert.repository

import com.dwje.alert.model.AiAgentState
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * AI 통합 대시보드가 읽는 Agent 실행 요약 (ax.tb_ai_agent_run).
 *
 * [EvalRunRepository] 와 역할이 다르다. 저쪽은 알림 전용 **상세**(조건·발생·억제·발송 건수)이고
 * 이쪽은 9종 Agent 가 같은 어휘로 한 줄씩 남기는 **통합 요약**이다. 화면은 Agent 마다 최신 1행만
 * 보므로 여기에 상세를 담을 이유가 없다. 둘 다 남긴다 — 상세를 요약으로 대체하지 않는다.
 *
 * 이 엔진은 그중 ⑨ 이상 알림 한 칸만 맡는다. 나머지 ①~⑧ 은 API 쪽이 채운다.
 *
 * **여기서 나는 오류는 밖으로 던지지 않는다.** 부가 기록이지 본업이 아니어서 알림 발송을
 * 깨뜨려서는 안 된다. 실패는 WARN 한 번으로 끝낸다 — 1분 주기라 매 틱 같은 WARN 을 쌓으면
 * 정작 봐야 할 발송 오류가 로그에서 묻힌다.
 */
@Repository
class AgentRunRepository(private val jdbc: NamedParameterJdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var lastWarning: String? = null

    /**
     * 틱 1회를 Agent ⑨ 의 실행 1행으로 남긴다.
     *
     * @param state          AI_AGENT_STATE — 정상 OK, 오류 ERROR
     * @param throughput     처리량 한 줄 요약 (50자 상한)
     * @param elapsedMs      틱 소요 시간. tb_alm_eval_run.duration_ms 와 같은 값
     * @param message        실패 사유. 정상이면 null (500자 상한)
     * @return 남겼으면 true. 실패했거나 ⑨ 행을 못 찾았으면 false
     */
    fun record(
        state: AiAgentState,
        throughput: String?,
        elapsedMs: Int,
        message: String?,
    ): Boolean {
        // agent_id 를 박지 않고 agent_no 로 찾는다. 번호는 화면·시드가 정하는 값이라
        // 9 라고 박아 두면 재적재로 채번이 밀리는 순간 엉뚱한 Agent 칸에 쓰게 된다.
        val sql = """
            INSERT INTO ax.tb_ai_agent_run
                   (agent_id, run_at, state_cd, throughput_txt, elapsed_ms, message, err_flg)
            SELECT a.agent_id, now(), :stateCd, :throughput, :elapsedMs, :message, :errFlg
              FROM ax.tb_ai_agent a
             WHERE a.agent_no = :agentNo
        """.trimIndent()

        val rows = runCatching {
            jdbc.update(
                sql,
                MapSqlParameterSource()
                    .addValue("agentNo", AGENT_NO)
                    .addValue("stateCd", state.name)
                    .addValue("throughput", throughput?.take(50))
                    .addValue("elapsedMs", elapsedMs)
                    .addValue("message", message?.take(500))
                    .addValue("errFlg", if (state == AiAgentState.ERROR) "Y" else "N"),
            )
        }.getOrElse {
            warnOnce("AI 대시보드용 Agent $AGENT_NO 실행 기록에 실패했습니다(알림 발송에는 영향 없음): ${it.message}")
            return false
        }

        if (rows == 0) {
            // 조건절이 걸러 낸 것이라 오류가 나지 않는다. 조용히 지나가면 화면은 영원히 IDLE 이다.
            warnOnce("ax.tb_ai_agent 에 agent_no='$AGENT_NO' 행이 없어 Agent 실행 기록을 건너뜁니다.")
            return false
        }

        lastWarning = null
        return true
    }

    /** 같은 사유가 반복될 때 로그를 한 번만 남긴다 (1분 주기라 그대로 두면 하루 1,440줄이다) */
    private fun warnOnce(message: String) {
        if (message == lastWarning) return
        lastWarning = message
        log.warn(message)
    }

    companion object {
        /** ax.tb_ai_agent 의 ⑨ — '이상 알림 : 임계 초과 · 패턴 이상 감지 및 발송'. 이 엔진이 하는 일 */
        private const val AGENT_NO = "⑨"
    }
}
