package com.dwje.alert.repository

import com.dwje.alert.model.DurationKind
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * 판정 규칙의 숫자를 공통코드에서 읽는다 (V35 가 채운 attr1/attr2).
 *
 * `M30` → 30분, `C10M` → 600초, `D0820` → 08:00~20:00 을 Kotlin `when` 절에 박지 않는 이유는
 * 중복 억제 창을 45분으로 바꾸는 일이 배포가 되면 안 되기 때문이다. 더 나쁜 것은 코드명
 * 파싱이다 — `D0820` 에서 시간을 떼어 쓰면 코드명을 바꾸는 순간 판정이 조용히 틀어진다.
 *
 * 값은 5분간 캐시한다. 화면에서 코드를 고쳐도 다음 갱신에 반영된다 —
 * 매 틱 전체를 다시 읽으면 1분마다 쓸데없는 질의가 나가고, 영원히 캐시하면
 * 코드를 고쳐도 엔진을 재기동해야 한다.
 */
@Repository
class CodeRepository(private val jdbc: NamedParameterJdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    private data class Attrs(val codeNm: String?, val attr1: String?, val attr2: String?)

    @Volatile
    private var cache: Map<String, Attrs> = emptyMap()

    @Volatile
    private var loadedAt: Instant = Instant.EPOCH

    private fun attrs(groupCd: String, code: String?): Attrs? {
        if (code.isNullOrBlank()) return null
        refreshIfStale()
        return cache["$groupCd|$code"]
    }

    @Synchronized
    private fun refreshIfStale() {
        if (Duration.between(loadedAt, Instant.now()) < TTL) return
        val sql = """
            SELECT group_cd, code, code_nm, attr1, attr2
              FROM ax.tb_sys_code
             WHERE group_cd IN ('ALM_OP', 'ALM_DURATION', 'ALM_DEDUP', 'ALM_WINDOW',
                                'ALM_SEVERITY', 'ALM_CHANNEL', 'MET_UNIT')
               AND use_flg = 'Y'
        """.trimIndent()
        val loaded = HashMap<String, Attrs>()
        jdbc.query(sql, emptyMap<String, Any>()) { rs ->
            loaded["${rs.getString("group_cd")}|${rs.getString("code")}"] =
                Attrs(rs.getString("code_nm"), rs.getString("attr1"), rs.getString("attr2"))
        }
        cache = loaded
        loadedAt = Instant.now()
        log.debug("판정 코드 {}건을 읽었습니다.", loaded.size)
    }

    /** 코드의 표시명. 메일 제목·본문에 '위험'·'메일' 처럼 사람이 읽는 말을 쓰기 위한 것 */
    fun labelOf(groupCd: String, code: String?): String =
        attrs(groupCd, code)?.codeNm ?: code.orEmpty()

    /**
     * 비교 연산자 (ALM_OP.attr1).
     * `RATE` 는 연산자가 아니라 '직전 대비 변화율(%)' 판정이라 별도로 다룬다.
     */
    fun operatorOf(opCd: String): String? = attrs("ALM_OP", opCd)?.attr1

    /** 지속 조건이 요구하는 연속 시간(초) — ALM_DURATION.attr1 */
    fun durationSeconds(durationCd: String): Long =
        attrs("ALM_DURATION", durationCd)?.attr1?.toLongOrNull() ?: 0L

    /** 지속 조건 판정 방식 — ALM_DURATION.attr2 (CONT / AVG / CLOSE) */
    fun durationKind(durationCd: String): DurationKind =
        DurationKind.of(attrs("ALM_DURATION", durationCd)?.attr2)

    /**
     * 중복 억제 창(분). `-1` 은 달력일 1회(DAY_ONCE), `0` 은 억제 없음.
     * 코드를 못 찾으면 0 — 억제하지 않는다. 모르는 값 때문에 알림을 삼키지 않는다.
     */
    fun dedupMinutes(dedupCd: String): Int =
        attrs("ALM_DEDUP", dedupCd)?.attr1?.toIntOrNull() ?: 0

    /**
     * 유효 시간대 (ALM_WINDOW.attr1 ~ attr2).
     * 폐지된 지정 시각 시간대는 사용하지 않습니다.
     */
    fun windowRange(windowCd: String): Pair<LocalTime, LocalTime>? {
        val a = attrs("ALM_WINDOW", windowCd) ?: return null
        val from = a.attr1?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
        val toRaw = a.attr2 ?: return null
        // '24:00' 은 LocalTime 이 받지 않는다. 하루 전체를 뜻하므로 23:59:59.999 로 접는다.
        val to = if (toRaw == "24:00") LocalTime.MAX else runCatching { LocalTime.parse(toRaw) }.getOrNull()
            ?: return null
        return from to to
    }

    companion object {
        private val TTL: Duration = Duration.ofMinutes(5)
    }
}
