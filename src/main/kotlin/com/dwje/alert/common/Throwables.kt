package com.dwje.alert.common

/**
 * 예외를 한 줄로 요약한다.
 *
 * Spring 의 `BadSqlGrammarException.message` 에는 **실행한 SQL 전문** 이 들어 있다.
 * 그대로 ax.tb_alm_eval_run.message 에 넣으면 실행 이력 한 행이 SQL 50줄로 채워져
 * 화면에서 읽을 수 없게 된다 (실제로 그렇게 들어갔다).
 *
 * 원인 사슬 앞쪽 3개만, 각각 한 줄로 줄여 남긴다 — 무엇이 터졌는지 아는 데는 그것으로 충분하고,
 * 자세한 것은 파일 로그(alert.log)에 스택 트레이스가 그대로 남아 있다.
 */
object Throwables {

    fun describe(e: Throwable, limit: Int = 300): String =
        generateSequence(e) { it.cause }
            .take(3)
            .joinToString(" ← ") { "${it.javaClass.simpleName}: ${firstLine(it.message)}" }
            .take(limit)

    /** 여러 줄짜리 메시지(SQL 전문 등)는 첫 줄만 쓴다 */
    private fun firstLine(message: String?): String {
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) return "(메시지 없음)"
        return text.lineSequence().first().trim().take(160)
    }
}
