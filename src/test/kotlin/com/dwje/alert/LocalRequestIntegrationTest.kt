package com.dwje.alert

import com.dwje.alert.model.*
import com.dwje.alert.repository.*
import com.dwje.alert.service.*
import com.dwje.alert.service.collector.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlin.test.*

/** 명시적으로 선택한 로컬 DB에서 검증하며 모든 시험 변경은 롤백합니다. */
@EnabledIfEnvironmentVariable(named = "ALERT_LOCAL_INTEGRATION", matches = "1")
class LocalRequestIntegrationTest {
    private val ds = DriverManagerDataSource("jdbc:postgresql://localhost:5432/dwjedb", "dwje_local", "dwje_local")
    private val jdbc = NamedParameterJdbcTemplate(ds)
    private fun rollback(block: () -> Unit) {
        TransactionTemplate(DataSourceTransactionManager(ds)).executeWithoutResult { status ->
            try { block() } finally { status.setRollbackOnly() }
        }
    }
    private fun def(code: String) = CollectDef(0, code, code, "BUILTIN", code, ScopeDim.NONE,
        300, 60, null, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TEN, null, null)

    @Test
    fun `정지 승인대기 잠김 계정은 발송 수신자에서 제외합니다`() = rollback {
        val repo = RecipientRepository(jdbc)
        val groups = listOf(11)
        val channels = listOf("MAIL", "POPUP", "SMS", "MSG")
        assertTrue(repo.findTargets(groups, channels).any { it.userId == "10004" })
        for (state in listOf("SUSPENDED", "PENDING", "LOCKED")) {
            jdbc.update("UPDATE ax.tb_sys_user SET user_state_cd=:state WHERE user_id='10004'", mapOf("state" to state))
            assertFalse(repo.findTargets(groups, channels).any { it.userId == "10004" })
        }
        jdbc.update("UPDATE ax.tb_sys_user SET user_state_cd='ACTIVE' WHERE user_id='10004'", emptyMap<String, Any>())
        assertTrue(repo.findTargets(groups, channels).any { it.userId == "10004" })
    }



    @Test
    fun `메일 발송 주소는 계정 메일을 따르고 비었을 때만 수신자 사본을 씁니다`() = rollback {
        val repo = RecipientRepository(jdbc)
        fun mailOf10003() = repo.findTargets(listOf(11), listOf("MAIL")).single { it.userId == "10003" }.destAddr
        jdbc.update("UPDATE ax.tb_sys_user SET user_state_cd='ACTIVE', email='ae-acct-10003@wonderslab.test' WHERE user_id='10003'", emptyMap<String, Any>())
        jdbc.update("UPDATE ax.tb_alm_recipient SET email='ae-stale-10003@dwje.test' WHERE user_id='10003'", emptyMap<String, Any>())
        assertEquals("ae-acct-10003@wonderslab.test", mailOf10003())
        jdbc.update("UPDATE ax.tb_sys_user SET email=NULL WHERE user_id='10003'", emptyMap<String, Any>())
        assertEquals("ae-stale-10003@dwje.test", mailOf10003())
        jdbc.update("UPDATE ax.tb_sys_user SET email='' WHERE user_id='10003'", emptyMap<String, Any>())
        assertEquals("ae-stale-10003@dwje.test", mailOf10003())
    }

    @Test
    fun `삭제 예정 컬럼이 없는 조건 표에서도 목록과 단건 조회가 동작합니다`() = rollback {
        jdbc.jdbcTemplate.execute("""
            CREATE TEMP VIEW ae_v73_cond AS
            SELECT cond_id, cond_nm, severity_cd, metric_id, metric_desc, op_cd, threshold_val,
                   threshold_text, threshold_unit, duration_cd, target_scope_cd, target_desc,
                   window_cd, dedup_cd, blind_field_key, use_flg, last_eval_at
              FROM ax.tb_alm_cond
        """.trimIndent())
        val withoutAdvanced = object : NamedParameterJdbcTemplate(ds) {
            override fun <T : Any?> query(sql: String, params: org.springframework.jdbc.core.namedparam.SqlParameterSource,
                                         mapper: org.springframework.jdbc.core.RowMapper<T>): List<T> =
                super.query(sql.replace("ax.tb_alm_cond c", "pg_temp.ae_v73_cond c"), params, mapper)
        }
        val expected = ConditionRepository(jdbc).findActive()
        val repo = ConditionRepository(withoutAdvanced)
        assertEquals(expected, repo.findActive())
        expected.forEach { assertEquals(it, repo.findOne(it.condId)) }
        println("삭제 컬럼 없는 조회 검증 조건: ${expected.map { it.condId }}")
    }

    @Test
    fun `로컬 두 조건은 수집 단위 EQPT와 설비별 판정 키를 유지합니다`() {
        val metrics = MetricRepository(jdbc)
        val codes = CodeRepository(jdbc)
        val states = org.mockito.Mockito.mock(CondStateRepository::class.java)
        val conditions = ConditionRepository(jdbc).findActive().filter { it.condId in listOf(5, 6) }
        assertEquals(2, conditions.size)
        for (cond in conditions) {
            assertEquals(ScopeDim.EQPT, metrics.collectDimOf(cond.metricId!!))
            val at = metrics.lastValueAt(cond.metricId)!!.plusSeconds(1)
            val readingKeys = metrics.readValues(cond.metricId, ScopeDim.EQPT, at.minusSeconds(900)).keys
            org.mockito.Mockito.`when`(states.findByCond(cond.condId)).thenReturn(emptyMap())
            val result = TickResult()
            val breaches = ConditionEvaluator(metrics, states, codes, com.dwje.alert.config.AlertProperties())
                .evaluate(cond, at, result, persist = false)
            assertEquals(readingKeys.size, result.evalCnt)
            assertTrue(breaches.all { it.dim == ScopeDim.EQPT && it.scopeKey in readingKeys })
            println("조건 ${cond.condId}: EQPT 판정 ${result.evalCnt}, 키 $readingKeys, 위반 ${breaches.map { it.scopeKey }}")
        }
    }

    @Test
    fun `현재 로컬 이관 이력에서 두 지표를 직접 계산합니다`() {
        val now = OffsetDateTime.now()
        for (collector in listOf(SyncFailRateCollector(jdbc), SyncStaleCollector(jdbc))) {
            val points = collector.collect(def(collector.metricCd), now.minusHours(1), now)
            println("로컬 직접 계산 ${collector.metricCd}: ${points.map { it.value }}")
            assertTrue(points.all { it.value.signum() >= 0 })
        }
    }

    @Test
    fun `10004 정지 계정은 위반 발생에도 대기열을 만들지 않습니다`() = rollback {
        val props = com.dwje.alert.config.AlertProperties()
        val codes = CodeRepository(jdbc)
        val recipients = RecipientRepository(jdbc)
        val raiser = AlertRaiser(AlertRepository(jdbc), CondStateRepository(jdbc),
            SendQueueRepository(jdbc), SendLogRepository(jdbc), recipients, codes,
            MessageRenderer(codes, recipients, props), GroupReceiveWindow(codes))
        val cond = ConditionRepository(jdbc).findActive().first().copy(
            groupIds = listOf(11), channels = listOf("MAIL", "POPUP"),
            dedupCd = "AE_TEST_NO_DEDUP", blindFieldKey = null)
        val now = OffsetDateTime.parse("2026-10-01T12:00:00+09:00")
        fun raiseAndCount(): Long {
            val result = TickResult()
            raiser.raise(ConditionEvaluator.Breach(cond, "AE_TEST", ScopeDim.NONE,
                BigDecimal.TEN, now, "로컬 정지 계정 검증입니다", null, null), now, result)
            assertEquals(1, result.raiseCnt)
            return jdbc.queryForObject("SELECT count(*) FROM ax.tb_alm_send_queue WHERE user_id='10004' AND alert_id=(SELECT max(alert_id) FROM ax.tb_alm_alert)", emptyMap<String, Any>(), Long::class.java)!!
        }
        assertTrue(raiseAndCount() > 0)
        jdbc.update("UPDATE ax.tb_sys_user SET user_state_cd='SUSPENDED' WHERE user_id='10004'", emptyMap<String, Any>())
        assertEquals(0L, raiseAndCount())
        jdbc.update("UPDATE ax.tb_sys_user SET user_state_cd='ACTIVE' WHERE user_id='10004'", emptyMap<String, Any>())
    }


    @Test
    fun `그룹 시간대 밖만 SKIPPED이고 야간에는 건너뛰지 않습니다`() = rollback {
        val props = com.dwje.alert.config.AlertProperties()
        val codes = CodeRepository(jdbc)
        val recipients = org.mockito.Mockito.mock(RecipientRepository::class.java)
        val target = RecipientTarget(11, "10004", null, null, "POPUP", "10004", false, null, "D0820")
        org.mockito.Mockito.`when`(recipients.findTargets(listOf(11), listOf("POPUP"))).thenReturn(listOf(target))
        val raiser = AlertRaiser(AlertRepository(jdbc), CondStateRepository(jdbc),
            SendQueueRepository(jdbc), SendLogRepository(jdbc), recipients, codes,
            MessageRenderer(codes, recipients, props), GroupReceiveWindow(codes))
        val base = ConditionRepository(jdbc).findActive().first().copy(
            groupIds = listOf(11), channels = listOf("POPUP"), windowCd = "ALWAYS",
            dedupCd = "AE_TEST_NO_DEDUP", blindFieldKey = null)
        fun raise(hour: Int, groupWindowCd: String = "D0820", conditionWindowCd: String = "ALWAYS"): TickResult {
            val now = OffsetDateTime.parse("2026-10-01T12:00:00+09:00").withHour(hour)
            org.mockito.Mockito.`when`(recipients.findTargets(listOf(11), listOf("POPUP")))
                .thenReturn(listOf(target.copy(groupWindowCd = groupWindowCd)))
            val result = TickResult()
            raiser.raise(ConditionEvaluator.Breach(base.copy(windowCd = conditionWindowCd), "AE_WINDOW_TEST",
                ScopeDim.NONE, BigDecimal.TEN, now, "로컬 그룹 시간대 검증입니다", null, null), now, result)
            return result
        }
        val skipped = raise(21)
        assertEquals(1, skipped.skipCnt)
        assertEquals(0, skipped.queuedCnt)
        assertEquals("그룹 수신 시간대 밖(08:00~20:00)", jdbc.queryForObject(
            "SELECT fail_reason FROM ax.tb_alm_send_log WHERE alert_id=(SELECT max(alert_id) FROM ax.tb_alm_alert)",
            emptyMap<String, Any>(), String::class.java))
        assertEquals(1, raise(12).queuedCnt)
        // 야간 미수신 기능이 없어졌으므로 23시에도 그대로 보낸다.
        val night = raise(23, "ALWAYS")
        assertEquals(1, night.queuedCnt)
        assertEquals(0, night.skipCnt)
        assertEquals(0, raise(21, "ALWAYS", "D0820").queuedCnt)
        assertEquals(0, raise(12, "ALWAYS", "ONCE").queuedCnt)
    }

    @Test
    fun `부재이거나 야간 미수신으로 표시된 멤버도 야간에 대기열에 들어갑니다`() = rollback {
        // V74 적용 전 DB 에만 컬럼이 있다. 있으면 일부러 부재·야간 미수신으로 바꿔 두고 확인한다.
        val cols = jdbc.queryForList(
            "SELECT table_name || '.' || column_name FROM information_schema.columns " +
                "WHERE table_schema='ax' AND column_name IN ('recv_state_cd','night_recv')",
            emptyMap<String, Any>(), String::class.java).toSet()
        if ("tb_alm_recipient.recv_state_cd" in cols)
            jdbc.update("UPDATE ax.tb_alm_recipient SET recv_state_cd='ABSENT' WHERE user_id='10004'", emptyMap<String, Any>())
        if ("tb_alm_recipient.night_recv" in cols)
            jdbc.update("UPDATE ax.tb_alm_recipient SET night_recv=false WHERE user_id='10004'", emptyMap<String, Any>())
        if ("tb_alm_recip_group.night_recv" in cols)
            jdbc.update("UPDATE ax.tb_alm_recip_group SET night_recv=false WHERE group_id=11", emptyMap<String, Any>())
        jdbc.update("UPDATE ax.tb_alm_recip_group SET window_cd='ALWAYS' WHERE group_id=11", emptyMap<String, Any>())
        jdbc.update("UPDATE ax.tb_sys_user SET user_state_cd='ACTIVE' WHERE user_id='10004'", emptyMap<String, Any>())

        val props = com.dwje.alert.config.AlertProperties()
        val codes = CodeRepository(jdbc)
        val recipients = RecipientRepository(jdbc)
        assertTrue(recipients.findTargets(listOf(11), listOf("MAIL", "POPUP")).any { it.userId == "10004" },
            "부재로 표시된 멤버도 수신 대상입니다")
        val raiser = AlertRaiser(AlertRepository(jdbc), CondStateRepository(jdbc),
            SendQueueRepository(jdbc), SendLogRepository(jdbc), recipients, codes,
            MessageRenderer(codes, recipients, props), GroupReceiveWindow(codes))
        val cond = ConditionRepository(jdbc).findActive().first().copy(
            groupIds = listOf(11), channels = listOf("MAIL", "POPUP"), windowCd = "ALWAYS",
            dedupCd = "AE_TEST_NO_DEDUP", blindFieldKey = null)
        val now = OffsetDateTime.parse("2026-10-01T23:30:00+09:00")
        val result = TickResult()
        raiser.raise(ConditionEvaluator.Breach(cond, "AE_NIGHT_TEST", ScopeDim.NONE,
            BigDecimal.TEN, now, "부재·야간 제거 검증입니다", null, null), now, result)
        assertEquals(1, result.raiseCnt)
        val alertId = "(SELECT max(alert_id) FROM ax.tb_alm_alert)"
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM ax.tb_alm_send_queue WHERE user_id='10004' AND alert_id=$alertId",
            emptyMap<String, Any>(), Long::class.java)!! > 0, "야간에도 대기열에 들어가야 합니다")
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM ax.tb_alm_send_log WHERE send_result_cd='SKIPPED' AND alert_id=$alertId",
            emptyMap<String, Any>(), Long::class.java))
        println("부재·야간 표시 컬럼(롤백): $cols, 결과 queued=${result.queuedCnt} skip=${result.skipCnt}")
    }

    @Test
    fun `그룹 시간대를 공통코드로 판정합니다`() {
        val window = GroupReceiveWindow(CodeRepository(jdbc))
        val now = OffsetDateTime.parse("2026-10-01T21:00:00+09:00")
        assertEquals("그룹 수신 시간대 밖(08:00~20:00)", window.skipReason("D0820", now))
        assertNull(window.skipReason("D0820", now.withHour(12)))
        assertNull(window.skipReason("D0820", now.withHour(8)))
        assertNull(window.skipReason("D0820", now.withHour(20)))
        assertNotNull(window.skipReason("WORKDAY", now.plusDays(2).withHour(12)))
    }

    @Test
    fun `점검 실패를 매핑 수로 가중하고 분모가 없으면 적재하지 않습니다`() = rollback {
        val to = OffsetDateTime.parse("2090-01-01T12:00:00+09:00")
        val from = to.minusHours(1)
        val collector = SyncFailRateCollector(jdbc)
        assertTrue(collector.collect(def(collector.metricCd), from, to).isEmpty())
        jdbc.update("INSERT INTO ax.tb_sync_run(run_id,mode_cd,state_cd,ended_at) VALUES ('AE_TEST_PREFLIGHT','MANUAL','PREFLIGHT_FAIL',:at)", mapOf("at" to to.minusMinutes(10)))
        jdbc.update("INSERT INTO ax.tb_sync_job(job_id,map_id,sync_kind_cd,state_cd,ended_at) SELECT 'AE_TEST_DONE',min(map_id),'FULL','DONE',:at FROM ax.tb_sync_map", mapOf("at" to to.minusMinutes(20)))
        jdbc.update("INSERT INTO ax.tb_sync_job(job_id,map_id,sync_kind_cd,state_cd,ended_at) SELECT 'AE_TEST_FAIL',min(map_id),'FULL','FAIL',:at FROM ax.tb_sync_map", mapOf("at" to to.minusMinutes(15)))
        val mappings = jdbc.queryForObject("SELECT count(*) FROM ax.tb_sync_map WHERE use_flg='Y'", emptyMap<String, Any>(), Long::class.java)!!
        val expected = BigDecimal(mappings + 1).multiply(BigDecimal(100)).divide(BigDecimal(mappings + 2), 4, java.math.RoundingMode.HALF_UP)
        assertEquals(expected, collector.collect(def(collector.metricCd), from, to).single().value)
        val stale = SyncStaleCollector(jdbc)
        assertEquals(BigDecimal("20.0000"), stale.collect(def(stale.metricCd), from, to).single().value)
        jdbc.update("INSERT INTO ax.tb_sync_run(run_id,mode_cd,state_cd,ended_at) VALUES ('AE_TEST_GROUPWARE','GROUPWARE','DONE',:at)", mapOf("at" to to.minusMinutes(1)))
        jdbc.update("INSERT INTO ax.tb_sync_job(job_id,map_id,sync_kind_cd,state_cd,ended_at,run_id) SELECT 'AE_TEST_GW',min(map_id),'FULL','DONE',:at,'AE_TEST_GROUPWARE' FROM ax.tb_sync_map", mapOf("at" to to.minusMinutes(1)))
        assertEquals(BigDecimal("20.0000"), stale.collect(def(stale.metricCd), from, to).single().value)
        assertEquals(expected, collector.collect(def(collector.metricCd), from, to).single().value)
    }
}
