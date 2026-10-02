package com.dwje.alert.cli

import com.dwje.alert.model.RunTrigger
import org.springframework.boot.ApplicationArguments
import org.springframework.scheduling.support.CronExpression
import java.time.ZoneId

/**
 * 실행 옵션.
 *
 * 인자가 없으면 상주 모드로 뜨고 설정된 주기(기본 1분)로 판정한다.
 *
 * ```
 *   java -jar alert-engine.jar                          # 상주, 1분마다
 *   java -jar alert-engine.jar --interval=30s           # 상주, 30초마다
 *   java -jar alert-engine.jar --cron="0 * * * * *"     # 상주, cron 직접 지정
 *   java -jar alert-engine.jar --now                    # 즉시 1회 후 종료
 *   java -jar alert-engine.jar --now --cond=12          # 조건 12번만 1회 판정
 *   java -jar alert-engine.jar --now --dry-run          # 판정만. 알림·발송·상태 변경 없음
 * ```
 */
data class CliOptions(
    val mode: Mode,
    /** 상주 모드에서 쓸 cron 표현식 (6필드). 1회 실행이면 null */
    val cron: String?,
    val timezone: ZoneId,
    /** 판정할 조건 한정. 비면 활성 조건 전체 */
    val condIds: Set<Int>,
    /**
     * 판정만 하고 아무것도 바꾸지 않는다.
     * 상태(tb_alm_cond_state)도 쓰지 않는다 — 쓰면 다음 실제 판정의 연속 시간·억제 창이
     * 달라져 "확인만 했다" 가 아니게 된다.
     */
    val dryRun: Boolean,
    /** tb_alm_eval_run.triggered_by 에 남길 실행자 */
    val user: String,
) {
    enum class Mode { SCHEDULED, RUN_NOW }

    val trigger: RunTrigger
        get() = if (mode == Mode.SCHEDULED) RunTrigger.BATCH else RunTrigger.MANUAL

    fun describe(): String = when (mode) {
        Mode.SCHEDULED -> "상주 모드 — cron='$cron' ($timezone)"
        Mode.RUN_NOW -> "즉시 실행 모드 (1회 후 종료)"
    } + buildString {
        if (condIds.isNotEmpty()) append(", 조건=${condIds.sorted().joinToString(",")}")
        if (dryRun) append(", DRY-RUN")
    }

    companion object {

        fun parse(
            args: ApplicationArguments,
            defaultCron: String?,
            defaultInterval: String?,
            defaultZone: String,
        ): CliOptions {
            val now = args.containsOption("now")
            val cronOpt = args.single("cron")
            val intervalOpt = args.single("interval")

            val exclusive = listOfNotNull(
                "--now".takeIf { now },
                "--cron".takeIf { cronOpt != null },
                "--interval".takeIf { intervalOpt != null },
            )
            require(exclusive.size <= 1) {
                "${exclusive.joinToString(", ")} 은(는) 함께 쓸 수 없습니다. 하나만 지정하십시오."
            }

            val zone = runCatching { ZoneId.of(args.single("timezone") ?: defaultZone) }
                .getOrElse { throw IllegalArgumentException("--timezone 값이 올바르지 않습니다: ${args.single("timezone")}") }

            val mode = if (now) Mode.RUN_NOW else Mode.SCHEDULED

            val cron = if (mode == Mode.SCHEDULED) {
                // 우선순위 — 명령줄이 설정을 이긴다.
                //   --cron > --interval > schedule.cron > schedule.interval
                // 어느 것도 없으면 1분 주기로 떨어진다. "안 도는 스케줄" 보다는 낫다.
                (
                    cronOpt?.takeIf { it.isNotBlank() }
                        ?: intervalOpt?.let { intervalToCron(it) }
                        ?: defaultCron?.takeIf { it.isNotBlank() }
                        ?: defaultInterval?.takeIf { it.isNotBlank() }?.let { intervalToCron(it) }
                        ?: intervalToCron("1m")
                    ).also { validateCron(it) }
            } else {
                null
            }

            val condIds = args.single("cond")
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.map { it.toIntOrNull() ?: throw IllegalArgumentException("--cond 는 조건 번호(숫자)여야 합니다: $it") }
                ?.toSet()
                ?: emptySet()

            return CliOptions(
                mode = mode,
                cron = cron,
                timezone = zone,
                condIds = condIds,
                dryRun = args.containsOption("dry-run"),
                user = args.single("user") ?: "SYSTEM",
            )
        }

        /**
         * 반복 주기를 cron 으로 바꾼다. `--interval=30s` → `0/30 * * * * *`
         *
         * **벽시계 경계에 맞춘다.** 기동 시각부터 N초를 세는 방식이 아니라 매분 0초를
         * 기준으로 N초마다 실행한다. 재기동해도 판정 시각이 밀리지 않아 로그를 맞춰 보기 쉽다.
         *
         * 그래서 60(초·분) 또는 24(시간)를 나누어떨어지는 값만 받는다.
         */
        private fun intervalToCron(raw: String): String {
            val value = raw.trim().lowercase()
            require(value.isNotEmpty()) { "--interval 값이 비었습니다" }

            val match = Regex("""^(\d+)\s*(s|m|h|sec|min|hour)?$""").find(value)
                ?: throw IllegalArgumentException(
                    "--interval 은 숫자와 단위로 씁니다 (예: 1m, 30s, 1h). 단위를 빼면 분입니다: $raw",
                )
            val amount = match.groupValues[1].toIntOrNull()
                ?: throw IllegalArgumentException("--interval 값이 너무 큽니다: $raw")
            require(amount > 0) { "--interval 은 0보다 커야 합니다: $raw" }

            return when (match.groupValues[2].ifEmpty { "m" }) {
                "s", "sec" -> {
                    require(60 % amount == 0) { divisorMessage("초", amount, 60) }
                    "0/$amount * * * * *"
                }
                "m", "min" -> {
                    require(60 % amount == 0) { divisorMessage("분", amount, 60) }
                    "0 0/$amount * * * *"
                }
                else -> {
                    require(24 % amount == 0) { divisorMessage("시간", amount, 24) }
                    "0 0 0/$amount * * *"
                }
            }
        }

        private fun divisorMessage(unit: String, given: Int, whole: Int): String {
            val usable = (1..whole).filter { whole % it == 0 }.joinToString(", ")
            return "--interval 은 ${whole}${unit}을 나누어떨어지는 값이어야 벽시계에 정렬됩니다. " +
                "받은 값 $given$unit → 쓸 수 있는 값: $usable"
        }

        private fun validateCron(cron: String) {
            require(CronExpression.isValidExpression(cron)) {
                "cron 표현식이 올바르지 않습니다: '$cron' (Spring 6필드 — 초 분 시 일 월 요일)"
            }
        }

        /**
         * `--name=값` 을 읽는다. 같은 옵션이 여러 번 오면 마지막 것을 쓴다.
         *
         * **적어 놓고 값을 비운 경우는 거부한다.** `--interval=` 을 "안 준 것" 으로 다루면
         * 기본값으로 조용히 떨어져, 사용자는 자기가 지정한 값으로 도는 줄 안다.
         */
        private fun ApplicationArguments.single(name: String): String? {
            if (name !in optionNames) return null
            val value = getOptionValues(name)?.lastOrNull()?.trim()
            require(!value.isNullOrEmpty()) { "--$name 값이 비었습니다. 값을 주거나 옵션을 빼십시오." }
            return value
        }
    }
}
