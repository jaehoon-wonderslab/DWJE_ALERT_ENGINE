package com.dwje.alert.repository

import com.dwje.alert.model.QueuedSend
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * 발송 대기열 (ax.tb_alm_send_queue).
 *
 * 발생과 발송을 떼어 놓는 자리다. SMTP 가 죽어도 알림은 남고, 살아나면 재시도한다.
 * 발송 결과 기록은 ax.tb_alm_send_log 가 따로 맡는다 — 이 표를 덮어쓰면
 * "3번째 시도에 나갔다" 가 사라지기 때문이다.
 */
@Repository
class SendQueueRepository(private val jdbc: NamedParameterJdbcTemplate) {

    data class NewSend(
        val alertId: Long,
        val groupId: Int?,
        val userId: String?,
        val channelCd: String,
        val destAddr: String?,
        val subject: String,
        val body: String,
        val escLevel: Short = 0,
        val isProxy: Boolean = false,
        val proxyOfUserId: String? = null,
    )

    /**
     * 대기열에 넣는다.
     *
     * `ux_alm_send_queue_once` 덕분에 같은 (알림·수신자·채널·승격단계)는 한 번만 들어간다.
     * 엔진이 적재 도중에 죽어 재기동한 뒤 같은 알림을 다시 처리해도 두 번 가지 않는다 —
     * 코드가 아니라 DB 가 보장한다.
     */
    fun enqueue(rows: List<NewSend>): Int {
        if (rows.isEmpty()) return 0
        val sql = """
            INSERT INTO ax.tb_alm_send_queue
                   (alert_id, group_id, user_id, channel_cd, dest_addr,
                    subject, body, esc_level, is_proxy, proxy_of_user_id)
            VALUES (:alertId, :groupId, :userId, :channelCd, :destAddr,
                    :subject, :body, :escLevel, :isProxy, :proxyOf)
            ON CONFLICT DO NOTHING
        """.trimIndent()
        val batch = rows.map {
            MapSqlParameterSource()
                .addValue("alertId", it.alertId)
                .addValue("groupId", it.groupId)
                .addValue("userId", it.userId)
                .addValue("channelCd", it.channelCd)
                .addValue("destAddr", it.destAddr?.take(200))
                .addValue("subject", it.subject.take(300))
                .addValue("body", it.body)
                .addValue("escLevel", it.escLevel.toInt())
                .addValue("isProxy", it.isProxy)
                .addValue("proxyOf", it.proxyOfUserId)
        }.toTypedArray()
        return jdbc.batchUpdate(sql, batch).sum()
    }

    /**
     * 보낼 건을 집어간다.
     *
     * `FOR UPDATE SKIP LOCKED` 로 같은 행을 두 워커가 잡지 않는다.
     * 집어가는 순간 try_cnt 를 올린다 — 발송 중에 프로세스가 죽어도 무한 재시도가 되지 않는다.
     */
    fun claim(batchSize: Int, workerId: String): List<QueuedSend> {
        val sql = """
            WITH due AS (
                SELECT queue_id
                  FROM ax.tb_alm_send_queue
                 WHERE state_cd IN ('PENDING', 'FAIL')
                   AND next_try_at <= now()
                 ORDER BY next_try_at, queue_id
                 FOR UPDATE SKIP LOCKED
                 LIMIT :batchSize
            )
            UPDATE ax.tb_alm_send_queue q
               SET state_cd = 'SENDING', locked_by = :workerId, locked_at = now(),
                   try_cnt = q.try_cnt + 1
              FROM due
             WHERE q.queue_id = due.queue_id
            RETURNING q.queue_id, q.alert_id, q.group_id, q.user_id, q.channel_cd, q.dest_addr,
                      q.subject, q.body, q.esc_level, q.is_proxy, q.proxy_of_user_id, q.try_cnt
        """.trimIndent()
        return jdbc.query(
            sql,
            MapSqlParameterSource().addValue("batchSize", batchSize).addValue("workerId", workerId.take(100)),
        ) { rs, _ ->
            QueuedSend(
                queueId = rs.getLong("queue_id"),
                alertId = rs.getLong("alert_id"),
                groupId = rs.getObject("group_id") as? Int,
                userId = rs.getString("user_id"),
                channelCd = rs.getString("channel_cd"),
                destAddr = rs.getString("dest_addr"),
                subject = rs.getString("subject"),
                body = rs.getString("body"),
                escLevel = rs.getShort("esc_level"),
                isProxy = rs.getBoolean("is_proxy"),
                proxyOfUserId = rs.getString("proxy_of_user_id"),
                tryCnt = rs.getInt("try_cnt"),
            )
        }
    }

    fun markDone(queueId: Long) {
        jdbc.update(
            "UPDATE ax.tb_alm_send_queue SET state_cd = 'DONE', locked_by = NULL, last_error = NULL WHERE queue_id = :id",
            MapSqlParameterSource("id", queueId),
        )
    }

    /** 실패. 최대 시도를 넘기면 DEAD 로 두고 더 보지 않는다 */
    fun markFailed(queueId: Long, error: String?, nextTryAt: OffsetDateTime, dead: Boolean) {
        jdbc.update(
            """
            UPDATE ax.tb_alm_send_queue
               SET state_cd    = :state,
                   next_try_at = :nextTryAt,
                   locked_by   = NULL,
                   last_error  = :error
             WHERE queue_id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", queueId)
                .addValue("state", if (dead) "DEAD" else "FAIL")
                .addValue("nextTryAt", nextTryAt)
                .addValue("error", error?.take(500)),
        )
    }

    /**
     * SENDING 으로 굳은 행을 되돌린다.
     *
     * 엔진이 발송 도중 죽으면 그 행은 영원히 SENDING 으로 남아 아무도 집어가지 않는다.
     * try_cnt 는 이미 올라가 있으므로 회수해도 무한 재시도가 되지 않는다.
     */
    fun recoverStuck(olderThanSec: Long): Int = jdbc.update(
        """
        UPDATE ax.tb_alm_send_queue
           SET state_cd = 'PENDING', locked_by = NULL,
               last_error = '발송 중 엔진이 종료되어 회수함'
         WHERE state_cd = 'SENDING'
           AND locked_at < now() - make_interval(secs => :sec)
        """.trimIndent(),
        MapSqlParameterSource("sec", olderThanSec),
    )

    /** 끝난 대기열 행 정리. 기록은 tb_alm_send_log 에 남아 있다 */
    fun purgeDone(days: Int): Int = jdbc.update(
        """
        DELETE FROM ax.tb_alm_send_queue
         WHERE state_cd = 'DONE' AND ins_date < now() - make_interval(days => :days)
        """.trimIndent(),
        MapSqlParameterSource("days", days),
    )

    fun countPending(): Int = jdbc.queryForObject(
        "SELECT count(*) FROM ax.tb_alm_send_queue WHERE state_cd IN ('PENDING', 'FAIL')",
        MapSqlParameterSource(),
        Int::class.java,
    ) ?: 0
}
