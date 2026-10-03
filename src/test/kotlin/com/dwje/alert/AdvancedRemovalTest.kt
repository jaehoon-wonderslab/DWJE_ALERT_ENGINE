package com.dwje.alert

import com.dwje.alert.config.AlertProperties
import com.dwje.alert.model.*
import com.dwje.alert.repository.*
import com.dwje.alert.service.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlin.test.*

/** 조건별 설정을 제거한 뒤의 고정 동작을 검증합니다. */
class AdvancedRemovalTest {
    private val now = OffsetDateTime.parse("2026-10-03T08:00:00+09:00")
    private val metrics = mock(MetricRepository::class.java)
    private val states = mock(CondStateRepository::class.java)
    private val codes = mock(CodeRepository::class.java)
    private val evaluator = ConditionEvaluator(metrics, states, codes, AlertProperties())
    private fun condition(duration: String = "NOW") = AlertCondition(
        condId = 1, name = "시험 조건", severity = "WARN", metricId = 1, metricCd = "TEST", metricNm = "시험 지표",
        metricDesc = "시험 지표", unitCd = "PCT", opCd = "GT", threshold = BigDecimal.TEN, thresholdText = "10",
        thresholdUnit = "PCT", durationCd = duration, targetScopeCd = "ALL", targetDesc = "전체 설비",
        windowCd = "ALWAYS", dedupCd = "NONE", blindFieldKey = null, channels = listOf("POPUP"),
        groupIds = listOf(11), pickTargets = emptyList())

    private fun prepare(duration: String = "NOW", kind: DurationKind = DurationKind.CONT, value: String = "20") {
        `when`(metrics.lastValueAt(1)).thenReturn(now)
        `when`(metrics.collectDimOf(1)).thenReturn(ScopeDim.EQPT)
        `when`(codes.durationKind(duration)).thenReturn(kind)
        `when`(codes.durationSeconds(duration)).thenReturn(0L)
        `when`(codes.operatorOf("GT")).thenReturn(">")
        `when`(states.findByCond(1)).thenReturn(emptyMap())
        val reading = MetricRepository.Reading("EQ001", BigDecimal(value), now, BigDecimal(value), null, 1)
        `when`(metrics.readValues(1, ScopeDim.EQPT, now.minusSeconds(900))).thenReturn(mapOf("EQ001" to reading))
    }

    @Test
    fun `수집 단위로 설비별 판정하고 다음 평가는 60초 뒤입니다`() {
        prepare()
        val breaches = evaluator.evaluate(condition(), now, TickResult())
        assertEquals("EQ001", breaches.single().scopeKey)
        assertEquals(ScopeDim.EQPT, breaches.single().dim)
        verify(states).upsertEvaluation(1, "EQ001", CondStateCd.BREACH, BigDecimal("20"), now, now, 1, now.plusSeconds(60))
    }


    @Test
    fun `수집 정의가 없으면 전체 지표 단위로 판정합니다`() {
        prepare()
        `when`(metrics.collectDimOf(1)).thenReturn(null)
        val reading = MetricRepository.Reading("*", BigDecimal("20"), now, BigDecimal("20"), null, 1)
        `when`(metrics.readValues(1, ScopeDim.NONE, now.minusSeconds(900))).thenReturn(mapOf("*" to reading))
        val breach = evaluator.evaluate(condition(), now, TickResult()).single()
        assertEquals(ScopeDim.NONE, breach.dim)
        assertEquals("*", breach.scopeKey)
    }

    @Test
    fun `과거 조건의 긴 예약 시각이 있어도 60초가 지나면 평가합니다`() {
        prepare()
        `when`(states.findByCond(1)).thenReturn(mapOf("EQ001" to CondStateRow.initial(1, "EQ001", now).copy(
            lastEvalAt = now.minusSeconds(60), nextEvalAt = now.plusHours(1))))
        assertEquals(1, evaluator.evaluate(condition(), now, TickResult()).size)
        `when`(states.findByCond(1)).thenReturn(mapOf("EQ001" to CondStateRow.initial(1, "EQ001", now).copy(
            lastEvalAt = now.minusSeconds(59), nextEvalAt = now.minusHours(1))))
        assertTrue(evaluator.evaluate(condition(), now, TickResult()).isEmpty())
    }

    @Test
    fun `정상 복귀는 평가 상태만 정상화하며 알림 자동 해제 경로가 없습니다`() {
        prepare(value = "5")
        `when`(states.findByCond(1)).thenReturn(mapOf("EQ001" to CondStateRow.initial(1, "EQ001", now).copy(
            state = CondStateCd.BREACH, lastEvalAt = now.minusMinutes(1), lastAlertId = 100)))
        assertTrue(evaluator.evaluate(condition(), now, TickResult()).isEmpty())
        verify(states).upsertEvaluation(1, "EQ001", CondStateCd.NORMAL, BigDecimal("5"), now, null, 0, now.plusSeconds(60))
        assertFalse(AlertRepository::class.java.methods.any { it.name == "markResolved" })
    }

    @Test
    fun `일 마감과 일 1회는 08시 이전에 건너뛰고 08시에 평가합니다`() {
        for (duration in listOf("DAY_CLOSE", "DAY_ONCE")) {
            prepare(duration, DurationKind.CLOSE)
            assertTrue(evaluator.evaluate(condition(duration), now.minusSeconds(1), TickResult()).isEmpty())
            assertEquals(1, evaluator.evaluate(condition(duration), now, TickResult()).size)
            verify(states, atLeastOnce()).upsertEvaluation(1, "EQ001", CondStateCd.BREACH, BigDecimal("20"), now, now, 1, now.plusDays(1))
            `when`(states.findByCond(1)).thenReturn(mapOf("EQ001" to CondStateRow.initial(1, "EQ001", now).copy(
                lastEvalAt = now.withOffsetSameInstant(java.time.ZoneOffset.UTC), nextEvalAt = now.plusDays(1))))
            assertTrue(evaluator.evaluate(condition(duration), now, TickResult()).isEmpty())
        }
    }

    @Test
    fun `고정 틀과 민감 값 마스킹을 유지합니다`() {
        `when`(codes.labelOf("ALM_SEVERITY", "WARN")).thenReturn("주의")
        `when`(codes.labelOf("MET_UNIT", "PCT")).thenReturn("%")
        `when`(codes.operatorOf("GT")).thenReturn(">")
        val recipients = mock(RecipientRepository::class.java)
        val renderer = MessageRenderer(codes, recipients, AlertProperties())
        val ctx = MessageRenderer.Context(condition(), 123, "EQ001", BigDecimal("20"), now, "근거 값 20입니다")
        val plain = renderer.render(ctx, null)
        assertEquals("[주의] 시험 조건 — EQ001 시험 지표 20% (> 10%) http://localhost:8090/alert/list?alertId=123", plain.body.lineSequence().first())
        val masked = renderer.render(ctx.copy(cond = condition().copy(blindFieldKey = "cost")), null)
        assertTrue(masked.body.startsWith("[주의] 시험 조건 — EQ001 시험 지표 ***% (> ***%)"))
        assertFalse(masked.body.contains("근거 값 20입니다"))
    }
}
