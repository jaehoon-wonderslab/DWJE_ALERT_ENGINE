package com.dwje.alert.cli

import com.dwje.alert.config.AlertProperties
import com.dwje.alert.repository.EvalRunRepository
import com.dwje.alert.service.AlertEngineScheduler
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * 기동 진입점.
 *
 * 인자를 해석해 상주 모드로 스케줄을 등록하거나, 즉시 1회 실행하고 종료한다.
 * 종료 코드 — 0 정상, 1 실행 실패, 2 옵션·기동 오류.
 */
@Component
@Order(1)
class AlertCliRunner(
    private val props: AlertProperties,
    private val scheduler: AlertEngineScheduler,
    private val runRepo: EvalRunRepository,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (args.containsOption("help") || args.containsOption("h")) {
            printUsage()
            exit(0)
            return
        }

        val options = try {
            CliOptions.parse(
                args = args,
                defaultCron = props.schedule.cron,
                defaultInterval = props.schedule.interval,
                defaultZone = props.schedule.timezone,
            )
        } catch (e: IllegalArgumentException) {
            log.error("옵션 오류 — {}", e.message)
            printUsage()
            exit(2)
            return
        }

        // 스키마가 없으면 엔진은 아무 일도 못 한다. 기동을 막지는 않되(설정 점검은 할 수 있게)
        // 무엇이 없는지 먼저 알린다 — 조용히 0건만 처리하면 원인을 찾는 데 오래 걸린다.
        if (!runCatching { runRepo.isSchemaReady() }.getOrDefault(false)) {
            log.error(
                "엔진용 표가 없습니다. API 프로젝트의 src/main/resources/db/V35__alm_engine.sql 을 적용하십시오.",
            )
        }

        // 비정상 종료로 RUNNING 에 남은 실행을 정리한다. 남겨 두면 "지금도 돌고 있다" 로 보인다.
        runCatching { runRepo.abortStale() }
            .onSuccess { if (it > 0) log.warn("미완료 상태로 남아 있던 실행 {}건을 정리했습니다.", it) }
            .onFailure { log.warn("미완료 실행 정리를 건너뜁니다: {}", it.message) }

        log.info("실행 옵션 — {}", options.describe())

        when (options.mode) {
            CliOptions.Mode.SCHEDULED -> {
                scheduler.schedule(options)
                // 상주 — 스케줄러 스레드가 프로세스를 유지한다.
                // 종료 처리는 Spring 셧다운 훅이 AlertEngineScheduler.awaitRunningTick() 을
                // 부르는 쪽으로 일원화했다. 자체 훅을 따로 걸면 순서를 보장하지 못한다.
            }

            CliOptions.Mode.RUN_NOW -> exit(runOnce(options))
        }
    }

    private fun runOnce(options: CliOptions): Int = try {
        val result = scheduler.executeGuarded(options)
        when {
            result == null -> {
                log.warn("다른 인스턴스가 판정 중이어서 이번 요청은 수행되지 않았습니다.")
                1
            }
            else -> {
                log.info("실행 결과 — {}", result.summary())
                if (result.errors.isNotEmpty()) 1 else 0
            }
        }
    } catch (e: Exception) {
        log.error("실행이 중단되었습니다.", e)
        1
    }

    private fun exit(code: Int) {
        // 상주 스레드가 없으므로 컨텍스트를 닫으면 프로세스가 종료된다
        Thread {
            SpringApplication.exit(context, ExitCodeGenerator { code })
            Runtime.getRuntime().halt(code)
        }.start()
    }

    private fun printUsage() {
        log.info(
            Usage.text(
                scheduleDesc = with(props.schedule) {
                    when {
                        !cron.isNullOrBlank() -> "cron='$cron' 으로"
                        !interval.isNullOrBlank() -> "$interval 마다"
                        else -> "1분마다"
                    }
                },
                timezone = props.schedule.timezone,
            ),
        )
    }
}
