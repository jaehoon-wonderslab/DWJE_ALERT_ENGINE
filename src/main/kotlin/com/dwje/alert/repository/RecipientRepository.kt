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
 * [부재·야간 수신 기능은 없다 — 2026-10-03 제거]
 * 수신 그룹의 멤버는 계정이 활성(ACTIVE)이기만 하면 전원 받는다. 부재 제외·야간 제외는 하지 않는다.
 * ax.tb_alm_recipient.recv_state_cd · night_recv, ax.tb_alm_recip_group.night_recv 는
 * V74 에서 지워지므로 **이 질의는 그 컬럼을 읽지 않는다** — V74 적용 전후 어느 DB 에서도 동작한다.
 * 시간 제한은 그룹·조건의 유효 시간대(ALM_WINDOW)만 남는다.
 *
 * [메일은 계정 메일이 기준이다 — 2026-10-03]
 * ax.tb_alm_recipient.email 은 수신자 등록 때 계정에서 떠 둔 사본이라 계정 메일을 바꿔도 따라오지 않았다.
 * 그래서 MAIL 주소는 ax.tb_sys_user.email 을 먼저 쓰고, 계정 메일이 비었을 때만 수신자 행의 사본을 쓴다.
 * API(AlertConfigRepository.findTargetMembers · 수신자 목록)와 같은 규칙이다. 휴대전화·메신저는 수신자 행 값 그대로.
 *
 * [당직 대리 수신은 하지 않는다 — V36 에서 당번 기능이 빠졌다]
 * 2026-09-16 V36 이 ax.tb_alm_duty 를 지웠다(SY-05 화면의 「당번·승격」 탭 제거).
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

        return jdbc.query(
            TARGETS_SQL,
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

    companion object {
        /**
         * 수신 대상 질의. 시험에서 V74 로 지워질 컬럼을 읽지 않는지 확인하려고 밖으로 뺐다.
         * 활성 계정 × (조건 채널 ∩ 그룹 채널) 만 거른다 — 부재·야간 컬럼은 참조하지 않는다.
         * MAIL 주소는 계정 메일 우선(`COALESCE(NULLIF(u.email, ''), rc.email)`) — 시험이 이 식을 확인한다.
         */
        internal val TARGETS_SQL = """
            SELECT DISTINCT
                   m.group_id,
                   g.window_cd,
                   m.user_id,
                   u.user_nm,
                   u.dept_id,
                   gc.channel_cd,
                   CASE gc.channel_cd
                        WHEN 'MAIL'  THEN COALESCE(NULLIF(u.email, ''), rc.email)  -- 계정 메일 우선, 비면 수신자 사본
                        WHEN 'SMS'   THEN rc.mobile_no
                        WHEN 'MSG'   THEN rc.messenger_id
                        WHEN 'POPUP' THEN m.user_id     -- 팝업은 주소가 아니라 사람이 대상이다
                   END                                  AS dest_addr
              FROM ax.tb_alm_recip_group_member m
              JOIN ax.tb_alm_recip_group g          ON g.group_id = m.group_id AND g.use_flg = 'Y'
              JOIN ax.tb_alm_recipient rc           ON rc.user_id = m.user_id
              JOIN ax.tb_alm_recip_group_channel gc ON gc.group_id = m.group_id
              JOIN ax.tb_sys_user u                 ON u.user_id = m.user_id AND u.user_state_cd = 'ACTIVE'
             WHERE m.group_id = ANY (:groupIds)
               AND gc.channel_cd = ANY (:channels)
               -- 보낼 사람이 0명이면 호출 측이 그 사실을 발송 기록에 남긴다.
             ORDER BY m.group_id, m.user_id, gc.channel_cd
        """.trimIndent()
    }
}
