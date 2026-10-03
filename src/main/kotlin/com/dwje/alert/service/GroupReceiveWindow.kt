package com.dwje.alert.service

import com.dwje.alert.repository.CodeRepository
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** 그룹 시간대의 공통코드 속성을 읽어 발송 제외 사유를 돌려줍니다. */
@Component
class GroupReceiveWindow(private val codes: CodeRepository) {
    fun skipReason(windowCd: String, now: OffsetDateTime): String? {
        val range = codes.windowRange(windowCd) ?: return null
        val time = now.toLocalTime()
        val inRange = if (range.first <= range.second) {
            !time.isBefore(range.first) && !time.isAfter(range.second)
        } else {
            !time.isBefore(range.first) || !time.isAfter(range.second)
        }
        if (inRange && (windowCd != "WORKDAY" || now.dayOfWeek.value < 6)) return null
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        return "그룹 수신 시간대 밖(${range.first.format(fmt)}~${range.second.format(fmt)})"
    }
}
