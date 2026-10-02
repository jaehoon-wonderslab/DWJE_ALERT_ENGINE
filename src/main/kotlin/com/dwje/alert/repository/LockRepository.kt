package com.dwje.alert.repository

import com.dwje.alert.config.AlertProperties
import org.springframework.stereotype.Repository
import javax.sql.DataSource

/**
 * 다중 인스턴스 동시 실행 방지.
 *
 * 엔진을 두 대 띄워도 판정은 한 번만 돌아야 한다. 같은 조건이 두 곳에서 동시에
 * 판정되면 같은 알림이 두 번 나가거나, 상태(breach_since)를 서로 덮어쓴다.
 *
 * 발송(④)은 이 잠금 밖에서 `FOR UPDATE SKIP LOCKED` 로 나눠 가지므로 여러 워커가
 * 동시에 돌아도 된다 — 막아야 하는 것은 판정이지 발송이 아니다.
 */
@Repository
class LockRepository(
    private val dataSource: DataSource,
    private val props: AlertProperties,
) {

    /**
     * 세션 단위 advisory lock. 반환된 [AutoCloseable] 을 닫을 때 해제된다.
     *
     * @return 잠금을 얻었으면 해제 핸들, 이미 다른 인스턴스가 잡고 있으면 null
     */
    fun tryAdvisoryLock(): AutoCloseable? {
        val conn = dataSource.connection
        val acquired = conn.prepareStatement("SELECT pg_try_advisory_lock(?)").use { ps ->
            ps.setLong(1, props.engine.advisoryLockKey)
            ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
        if (!acquired) {
            conn.close()
            return null
        }
        return AutoCloseable {
            runCatching {
                conn.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
                    ps.setLong(1, props.engine.advisoryLockKey)
                    ps.execute()
                }
            }
            conn.close()
        }
    }
}
