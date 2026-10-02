package com.dwje.alert

import com.dwje.alert.cli.CliOptions
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 실행 옵션 해석.
 *
 * 여기서 잡으려는 것은 "조용히 다른 값으로 도는" 경우다.
 * 사용자가 지정한 주기가 무시되거나, 빈 값이 기본값으로 떨어지는 일이 없어야 한다.
 */
class CliOptionsTest {

    private fun parse(vararg args: String) = CliOptions.parse(
        args = DefaultApplicationArguments(*args),
        defaultCron = null,
        defaultInterval = "1m",
        defaultZone = "Asia/Seoul",
    )

    @Test
    fun `인자가 없으면 설정의 주기로 상주한다`() {
        val o = parse()
        assertEquals(CliOptions.Mode.SCHEDULED, o.mode)
        assertEquals("0 0/1 * * * *", o.cron)
    }

    @Test
    fun `초 단위 주기는 벽시계에 정렬되는 cron 이 된다`() {
        assertEquals("0/30 * * * * *", parse("--interval=30s").cron)
        assertEquals("0 0/5 * * * *", parse("--interval=5m").cron)
        assertEquals("0 0 0/2 * * *", parse("--interval=2h").cron)
    }

    @Test
    fun `경계에 정렬되지 않는 주기는 거부하고 쓸 수 있는 값을 알려 준다`() {
        val e = assertFailsWith<IllegalArgumentException> { parse("--interval=7m") }
        assertTrue(e.message!!.contains("쓸 수 있는 값"), "대안을 알려 줘야 한다: ${e.message}")
    }

    @Test
    fun `실행 모드 옵션을 함께 주면 거부한다`() {
        assertFailsWith<IllegalArgumentException> { parse("--now", "--interval=1m") }
    }

    @Test
    fun `옵션을 적고 값을 비우면 기본값으로 떨어지지 않고 거부한다`() {
        // 안 쓰는 것과 쓰고 비우는 것은 다른 일이다.
        // 조용히 기본값으로 돌면 사용자는 자기가 준 값으로 도는 줄 안다.
        assertFailsWith<IllegalArgumentException> { parse("--interval=") }
    }

    @Test
    fun `즉시 실행은 cron 을 만들지 않는다`() {
        val o = parse("--now", "--dry-run", "--cond=3,7")
        assertEquals(CliOptions.Mode.RUN_NOW, o.mode)
        assertEquals(null, o.cron)
        assertTrue(o.dryRun)
        assertEquals(setOf(3, 7), o.condIds)
    }

    @Test
    fun `조건 번호가 숫자가 아니면 거부한다`() {
        assertFailsWith<IllegalArgumentException> { parse("--now", "--cond=abc") }
    }
}
