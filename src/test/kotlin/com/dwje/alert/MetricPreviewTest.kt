package com.dwje.alert

import com.dwje.alert.config.AlertProperties
import com.dwje.alert.model.*
import com.dwje.alert.repository.MetricRepository
import com.dwje.alert.service.MetricCollectService
import com.dwje.alert.service.collector.MetricCollector
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlin.test.assertEquals

/** dry-run이 계산을 수행해도 수집 상태와 측정값을 쓰지 않는지 검증합니다. */
class MetricPreviewTest {
    @Test
    fun `수집 미리보기는 계산만 하고 저장소 쓰기를 호출하지 않습니다`() {
        val repo = mock(MetricRepository::class.java)
        val now = OffsetDateTime.parse("2026-10-01T12:00:00+09:00")
        val def = CollectDef(1, "SYNC_FAIL_RATE", "이관 실패율", "BUILTIN", "SYNC_FAIL_RATE",
            ScopeDim.NONE, 300, 60, null, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TEN, null, null)
        `when`(repo.findEnabledCollectDefs()).thenReturn(listOf(def))
        var calls = 0
        val collector = object : MetricCollector {
            override val metricCd = def.metricCd
            override fun collect(def: CollectDef, from: OffsetDateTime, to: OffsetDateTime): List<MetricPoint> {
                calls++
                assertEquals(now.minusHours(1), from)
                return listOf(MetricPoint(to, BigDecimal.TEN))
            }
        }
        val result = TickResult()
        MetricCollectService(repo, AlertProperties(), listOf(collector)).collect(now, result, persist = false)
        assertEquals(1, calls)
        assertEquals(0, result.collectedCnt)
        verify(repo).findEnabledCollectDefs()
        verifyNoMoreInteractions(repo)
    }
}
