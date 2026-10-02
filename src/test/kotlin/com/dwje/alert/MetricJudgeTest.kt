package com.dwje.alert

import com.dwje.alert.model.CollectDef
import com.dwje.alert.model.ScopeDim
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 지표 판정 방향.
 *
 * 가동률은 낮을수록 나쁘고 불량률은 높을수록 나쁘다. 지표마다 방향 설정을 따로 두지 않고
 * 기준값과 주의값의 대소로 읽는다 — SY-13 에 이미 들어 있는 값이라 새로 받을 필요가 없다.
 */
class MetricJudgeTest {

    private fun def(std: String, warn: String, crit: String) = CollectDef(
        metricId = 1, metricCd = "X", metricNm = "지표", modeCd = "BUILTIN", collectorCd = "X",
        dimCd = ScopeDim.EQPT, intervalSec = 300, lookbackMin = 60, sqlText = null,
        stdVal = BigDecimal(std), warnVal = BigDecimal(warn), critVal = BigDecimal(crit),
        lastRunAt = null, lastValueAt = null,
    )

    @Test
    fun `가동률은 낮을수록 나쁘다`() {
        val uptime = def("85", "75", "65")   // 기준 85 · 주의 75 · 위험 65
        assertTrue(uptime.lowerIsWorse)
        assertEquals("NORMAL", uptime.judge(BigDecimal("90")))
        assertEquals("WARN", uptime.judge(BigDecimal("70")))
        assertEquals("CRIT", uptime.judge(BigDecimal("60")))
    }

    @Test
    fun `불량률은 높을수록 나쁘다`() {
        val defect = def("1.0", "3.0", "5.0")
        assertFalse(defect.lowerIsWorse)
        assertEquals("NORMAL", defect.judge(BigDecimal("0.5")))
        assertEquals("WARN", defect.judge(BigDecimal("4.0")))
        assertEquals("CRIT", defect.judge(BigDecimal("6.0")))
    }
}
