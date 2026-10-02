package com.dwje.alert

import com.dwje.alert.config.AlertProperties
import org.junit.jupiter.api.Test
import java.time.LocalTime
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 야간 구간 판정.
 *
 * 22:00~06:00 은 자정을 넘는다. 단순 비교(from <= t && t < to)로 짜면 야간이 언제나
 * 거짓이 되어, 야간 미수신으로 설정한 사람에게 새벽 알림이 그대로 나간다.
 */
class NightWindowTest {

    private val night = AlertProperties.Night(from = "22:00", to = "06:00")

    @Test
    fun `자정을 넘는 구간을 올바로 판정한다`() {
        assertTrue(night.contains(LocalTime.of(23, 30)))
        assertTrue(night.contains(LocalTime.of(0, 0)))
        assertTrue(night.contains(LocalTime.of(5, 59)))
        assertTrue(night.contains(LocalTime.of(22, 0)), "시작 시각은 포함한다")
    }

    @Test
    fun `주간은 야간이 아니다`() {
        assertFalse(night.contains(LocalTime.of(6, 0)), "종료 시각은 이미 주간이다")
        assertFalse(night.contains(LocalTime.of(9, 0)))
        assertFalse(night.contains(LocalTime.of(21, 59)))
    }

    @Test
    fun `자정을 넘지 않는 구간도 그대로 동작한다`() {
        val day = AlertProperties.Night(from = "01:00", to = "05:00")
        assertTrue(day.contains(LocalTime.of(3, 0)))
        assertFalse(day.contains(LocalTime.of(23, 0)))
    }
}
