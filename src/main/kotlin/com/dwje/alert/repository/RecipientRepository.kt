package com.dwje.alert.repository

import com.dwje.alert.model.RecipientTarget
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 누구에게 · 어떤 연락처로 보낼지 (SY-05 가 관리하는 표들).
 *
 * 조건(SY-04)은 수신 그룹 이름만 참조한다. 멤버와 연락처는 전부 이쪽이다.
 * 엔진은 그 경계를 그대로 지킨다 — 수신자 정보를 고치지 않는다.
 *
 * [당직 대리 수신은 하지 않는다 — V36 에서 당번 기능이 빠졌다]
 * 부재(recv_state_cd <> 'RECV')인 사람은 그냥 제외한다. 2026-09-16 V36 이
 * ax.tb_alm_duty 를 지웠기 때문이다(SY-05 화면의 「당번·승격」 탭 제거).
 * tb_alm_send_log · tb_alm_send_queue 의 is_proxy · proxy_of_user_id 컬럼은 남아 있지만
 * 지금은 항상 false · null 이다. 당번이 다시 생기면 이 질의만 고치면 된다.
 */
@Repository
class RecipientRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * 조건의 수신 그룹 × 채널을 사람 단위로 펼친다.
     *
     * 채널은 **조건이 고른 채널 ∩ 그룹이 받는 채널** 이다. 조건이 SMS 를 골랐어도
     * 그룹이 메일만 받는다면 보내지 않는다 — 그룹 설정이 사람에 더 가깝다.
     */
    fun findTargets(groupIds: List<Int>, channels: List<String>): List<RecipientTarget> {
        if (groupIds.isEmpty() || channels.isEmpty()) return emptyList()

        val sql = """
            SELECT DISTINCT
                   m.group_id,
                   g.window_cd,
                   m.user_id,
                   u.user_nm,
                   u.dept_id,
                   gc.channel_cd,
                   CASE gc.channel_cd
                        WHEN 'MAIL'  THEN rc.email
                        WHEN 'SMS'   THEN rc.mobile_no
                        WHEN 'MSG'   THEN rc.messenger_id
                        WHEN 'POPUP' THEN m.user_id     -- 팝업은 주소가 아니라 사람이 대상이다
                   END                                  AS dest_addr,
                   -- 그룹이 야간까지 받거나 본인이 야간 수신이면 새벽에도 보낸다
                   (g.night_recv OR rc.night_recv)       AS night_recv
              FROM ax.tb_alm_recip_group_member m
              JOIN ax.tb_alm_recip_group g          ON g.group_id = m.group_id AND g.use_flg = 'Y'
              JOIN ax.tb_alm_recipient rc           ON rc.user_id = m.user_id
              JOIN ax.tb_alm_recip_group_channel gc ON gc.group_id = m.group_id
              JOIN ax.tb_sys_user u                 ON u.user_id = m.user_id AND u.user_state_cd = 'ACTIVE'
             WHERE m.group_id = ANY (:groupIds)
               AND gc.channel_cd = ANY (:channels)
               -- 부재인 사람은 제외한다. 대리 수신은 없다(V36 에서 당번 표가 빠졌다).
               -- 그 결과 보낼 사람이 0명이 되면 호출 측이 그 사실을 발송 기록에 남긴다.
               AND rc.recv_state_cd = 'RECV'
             ORDER BY m.group_id, m.user_id, gc.channel_cd
        """.trimIndent()

        return jdbc.query(
            sql,
            MapSqlParameterSource()
                .addValue("groupIds", groupIds.toIntArray())
                .addValue("channels", channels.toTypedArray()),
        ) { rs, _ ->
            RecipientTarget(
                groupId = rs.getInt("group_id"),
                groupWindowCd = rs.getString("window_cd"),
                userId = rs.getString("user_id"),
                userNm = rs.getString("user_nm"),
                deptId = rs.getObject("dept_id") as? Int,
                channelCd = rs.getString("channel_cd"),
                destAddr = rs.getString("dest_addr"),
                nightRecv = rs.getBoolean("night_recv"),
                isProxy = false,
                proxyOfUserId = null,
            )
        }
    }

    /**
     * 이 사람이 이 데이터 항목을 볼 수 있는가 (ax.vw_user_data_perm).
     *
     * 메일은 화면과 달리 권한 검사를 통과해서 나가는 경로가 아니다. 여기서 확인하지 않으면
     * 데이터 접근 권한 설계가 메일로 그대로 샌다.
     *
     * 권한 행이 아예 없으면 **막는다**. 모르는 것을 보여 주는 쪽으로 기울면
     * 항목을 새로 만든 날 그 값이 전원에게 나간다.
     */
    fun isFieldAllowed(userId: String, fieldKey: String): Boolean = jdbc.query(
        """
        SELECT coalesce(bool_or(is_allowed), false) AS allowed
          FROM ax.vw_user_data_perm
         WHERE user_id = :userId AND field_key = :fieldKey
        """.trimIndent(),
        MapSqlParameterSource().addValue("userId", userId).addValue("fieldKey", fieldKey),
    ) { rs, _ -> rs.getBoolean("allowed") }.firstOrNull() ?: false
}
