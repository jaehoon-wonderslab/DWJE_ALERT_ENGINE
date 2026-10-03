package com.dwje.alert

import com.dwje.alert.repository.RecipientRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 부재·야간 수신 기능을 제거한 뒤의 수신 대상 질의.
 *
 * recv_state_cd · night_recv 는 V74 에서 지워진다. 질의가 하나라도 읽으면 V74 적용 직후
 * 모든 알림 발송이 SQL 오류로 멈추므로, 질의 본문에 두 컬럼이 없는지 확인한다.
 */
class RecipientNightAbsentRemovalTest {
    private val sql = RecipientRepository.TARGETS_SQL.lowercase()

    @Test
    fun `수신 대상 질의는 V74 로 지워질 컬럼을 읽지 않습니다`() {
        assertFalse("recv_state_cd" in sql, "부재 컬럼을 읽으면 V74 이후 실패합니다")
        assertFalse("night_recv" in sql, "야간 수신 컬럼을 읽으면 V74 이후 실패합니다")
    }

    @Test
    fun `비활성 계정 제외와 채널 교집합은 유지합니다`() {
        assertTrue("u.user_state_cd = 'active'" in sql)
        assertTrue("gc.channel_cd = any (:channels)" in sql)
    }

    @Test
    fun `메일 주소는 계정 메일을 먼저 쓰고 비었을 때만 수신자 사본을 씁니다`() {
        // 수신자 행 메일은 등록 때 떠 둔 사본이라 계정 메일 변경을 따라오지 않는다(2026-10-03)
        assertTrue("when 'mail'  then coalesce(nullif(u.email, ''), rc.email)" in sql)
        assertFalse("then rc.email" in sql, "수신자 사본만 읽으면 계정 메일 변경이 발송에 반영되지 않습니다")
    }
}
