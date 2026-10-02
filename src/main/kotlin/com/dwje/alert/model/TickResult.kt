package com.dwje.alert.model

/**
 * 한 틱의 집계. 그대로 ax.tb_alm_eval_run 한 행이 된다.
 *
 * 아무 일도 없던 틱은 이력을 남기지 않는다([isQuiet]). 1분 주기면 하루 1,440행이 쌓여
 * 정작 봐야 할 실행이 묻힌다 — 이관 엔진의 큐 폴링에서 같은 판단을 했다.
 */
data class TickResult(
    var condCnt: Int = 0,
    var evalCnt: Int = 0,
    var raiseCnt: Int = 0,
    var suppressCnt: Int = 0,
    var skipCnt: Int = 0,
    var queuedCnt: Int = 0,
    var sentCnt: Int = 0,
    var failCnt: Int = 0,
    var collectedCnt: Int = 0,
    var escalatedCnt: Int = 0,
    /** 조건 단위로 잡아 삼킨 오류. 하나가 전체를 죽이지 않는다 */
    val errors: MutableList<String> = mutableListOf(),
) {
    /** 남길 만한 일이 하나도 없었는가 */
    val isQuiet: Boolean
        get() = raiseCnt == 0 && suppressCnt == 0 && skipCnt == 0 && queuedCnt == 0 &&
            sentCnt == 0 && failCnt == 0 && escalatedCnt == 0 && collectedCnt == 0 && errors.isEmpty()

    val state: RunState
        get() = when {
            errors.isNotEmpty() && evalCnt == 0 && collectedCnt == 0 -> RunState.FAIL
            errors.isNotEmpty() -> RunState.PARTIAL
            else -> RunState.OK
        }

    /**
     * AI 통합 대시보드(Agent ⑨)에 올릴 상태 — 공통코드 AI_AGENT_STATE.
     *
     * [state] 의 FAIL · PARTIAL 을 함께 ERROR 로 접는다. 저쪽 어휘에는 '부분 실패' 가 없고,
     * 있다 해도 화면은 9종 Agent 를 한 줄씩만 보여 주므로 정상과 구분되기만 하면 된다.
     */
    val agentState: AiAgentState
        get() = if (errors.isEmpty()) AiAgentState.OK else AiAgentState.ERROR

    /**
     * tb_ai_agent_run.throughput_txt 에 넣을 한 줄 요약 (50자 상한).
     *
     * [summary] 와 달리 **0건도 적는다.** 화면의 '처리량' 칸은 틱마다 같은 자리에서 같은 항목을
     * 읽는 쪽이 눈으로 비교하기 쉽다. 반대로 로그는 일어난 일만 적는 편이 읽기 쉬워 둘을 나눴다.
     */
    fun throughput(): String =
        "조건 $condCnt · 판정 $evalCnt · 수집 $collectedCnt · 발송 $sentCnt".take(50)

    fun summary(): String = buildString {
        append("조건 ${condCnt}건 · 판정 ${evalCnt}")
        if (collectedCnt > 0) append(" · 수집 $collectedCnt")
        if (raiseCnt > 0) append(" · 발생 $raiseCnt")
        if (suppressCnt > 0) append(" · 억제 $suppressCnt")
        if (skipCnt > 0) append(" · 시간대제외 $skipCnt")
        if (queuedCnt > 0) append(" · 발송대기 $queuedCnt")
        if (sentCnt > 0) append(" · 발송 $sentCnt")
        if (failCnt > 0) append(" · 실패 $failCnt")
        if (escalatedCnt > 0) append(" · 승격 $escalatedCnt")
        if (errors.isNotEmpty()) append(" · 오류 ${errors.size}")
    }

    /** tb_alm_eval_run.message 에 넣을 문구 (2000자 상한) */
    fun message(): String? = errors.takeIf { it.isNotEmpty() }
        ?.joinToString(" | ")?.take(2000)
}
