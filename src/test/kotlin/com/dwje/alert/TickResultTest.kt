package com.dwje.alert

import com.dwje.alert.model.AiAgentState
import com.dwje.alert.model.RunState
import com.dwje.alert.model.TickResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 틱 집계.
 *
 * 1분 주기에서 조용한 틱까지 이력으로 남기면 하루 1,440행이 쌓여 정작 봐야 할 실행이 묻힌다.
 * "남길 만한 일이 있었는가" 의 판정이 틀리면 이력이 쓸모없어진다.
 */
class TickResultTest {

    @Test
    fun `판정만 하고 아무 일도 없었으면 조용한 틱이다`() {
        val r = TickResult(condCnt = 3, evalCnt = 120)
        assertTrue(r.isQuiet, "조건을 판정한 것만으로는 남길 일이 아니다")
    }

    @Test
    fun `알림이 하나라도 났으면 조용하지 않다`() {
        assertFalse(TickResult(raiseCnt = 1).isQuiet)
        assertFalse(TickResult(suppressCnt = 1).isQuiet, "억제도 남겨야 왜 안 왔는지 알 수 있다")
        assertFalse(TickResult(skipCnt = 1).isQuiet)
        assertFalse(TickResult(collectedCnt = 10).isQuiet)
    }

    @Test
    fun `조건 하나가 실패해도 나머지가 돌았으면 부분 실패다`() {
        val r = TickResult(evalCnt = 50, errors = mutableListOf("조건 3 판정 실패"))
        assertEquals(RunState.PARTIAL, r.state)
    }

    @Test
    fun `아무것도 못 했으면 실패다`() {
        val r = TickResult(errors = mutableListOf("V35 스키마 미적용"))
        assertEquals(RunState.FAIL, r.state)
    }

    @Test
    fun `대시보드 상태는 부분 실패도 ERROR 로 올린다`() {
        assertEquals(AiAgentState.OK, TickResult(condCnt = 3, evalCnt = 120).agentState)
        assertEquals(
            AiAgentState.ERROR,
            TickResult(evalCnt = 50, errors = mutableListOf("조건 3 판정 실패")).agentState,
            "AI_AGENT_STATE 에는 PARTIAL 이 없다. 부분 실패를 정상으로 보이게 하는 쪽이 더 나쁘다",
        )
        assertEquals(AiAgentState.ERROR, TickResult(errors = mutableListOf("V35 스키마 미적용")).agentState)
    }

    @Test
    fun `처리량 한 줄은 0건도 적고 50자를 넘지 않는다`() {
        assertEquals("조건 1 · 판정 1 · 수집 446 · 발송 0", TickResult(1, 1, collectedCnt = 446).throughput())
        assertTrue(
            TickResult(99999, 999999, collectedCnt = 999999, sentCnt = 99999).throughput().length <= 50,
            "throughput_txt 는 varchar(50) 이다",
        )
    }

    @Test
    fun `요약에는 일어난 일만 담는다`() {
        val summary = TickResult(condCnt = 2, evalCnt = 10, raiseCnt = 1, sentCnt = 3).summary()
        assertTrue(summary.contains("발생 1"))
        assertTrue(summary.contains("발송 3"))
        assertFalse(summary.contains("억제"), "0건은 적지 않는다")
    }
}
