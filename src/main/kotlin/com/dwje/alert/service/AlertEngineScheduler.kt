package com.dwje.alert.service

import com.dwje.alert.cli.CliOptions
import com.dwje.alert.common.Throwables
import com.dwje.alert.config.AlertProperties
import com.dwje.alert.model.RunState
import com.dwje.alert.model.TickResult
import com.dwje.alert.repository.AgentRunRepository
import com.dwje.alert.repository.ConditionRepository
import com.dwje.alert.repository.EvalRunRepository
import com.dwje.alert.repository.LockRepository
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronExpression
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 상주 모드 스케줄러.
 *
 * 중복 실행은 두 겹으로 막는다 (이관 엔진과 같은 방식).
 *   · 프로세스 내부 — [running] 플래그. 이전 틱이 다음 트리거까지 안 끝나면 건너뛴다
 *   · 프로세스 간   — PostgreSQL advisory lock. 두 대를 띄워도 판정은 한 곳에서만 돈다
 *
 * 판정이 겹치면 같은 알림이 두 번 나가거나 연속 시간(breach_since)을 서로 덮어쓴다.
 * 발송(④)은 대기열을 `SKIP LOCKED` 로 나눠 가지므로 여러 인스턴스가 함께 해도 된다.
 */
@Service
class AlertEngineScheduler(
    private val taskScheduler: TaskScheduler,
    private val lockRepo: LockRepository,
    private val condRepo: ConditionRepository,
    private val runRepo: EvalRunRepository,
    private val agentRunRepo: AgentRunRepository,
    private val collectService: MetricCollectService,
    private val evaluator: ConditionEvaluator,
    private val raiser: AlertRaiser,
    private val dispatcher: SendDispatcher,
    private val props: AlertProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val runIdFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private val hostName: String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown")
    private val version: String = javaClass.`package`?.implementationVersion ?: "1.0.0"

    @Volatile
    private var handle: ScheduledFuture<*>? = null

    fun schedule(options: CliOptions) {
        val cron = requireNotNull(options.cron) { "상주 모드인데 cron 이 지정되지 않았습니다" }
        handle = taskScheduler.schedule(Runnable { fire(options) }, CronTrigger(cron, options.timezone))

        val next = CronExpression.parse(cron).next(LocalDateTime.now(options.timezone))
        log.info(
            "스케줄 등록 — cron='{}' ({}) / 다음 실행 {}",
            cron, options.timezone, next?.format(fmt) ?: "없음",
        )
        log.info("엔진이 상주 모드로 기동했습니다. 즉시 1회 실행은 --now 옵션을 사용하십시오.")
    }

    /** 스케줄 트리거 진입점. 여기서 던진 예외가 스케줄 자체를 죽이지 않게 전부 잡는다 */
    private fun fire(options: CliOptions) {
        if (!running.compareAndSet(false, true)) {
            log.warn("이전 틱이 아직 실행 중입니다. 이번 트리거는 건너뜁니다.")
            return
        }
        try {
            executeGuarded(options)
        } catch (e: Exception) {
            log.error("틱 실행 중 오류가 발생했습니다. 스케줄은 유지됩니다.", e)
        } finally {
            running.set(false)
        }
    }

    /**
     * advisory lock 을 잡고 한 틱을 돈다.
     *
     * 잠금을 얻지 못하면 **아무 기록도 남기지 않는다.** 1분 주기에서 두 번째 인스턴스가
     * 매 틱 "건너뜀" 행을 남기면 하루 1,440행이 쌓여 이력이 그것으로 가득 찬다.
     * (이관 엔진은 5분 주기·하루 몇 번이라 건너뜀도 남겼지만 여기는 주기가 다르다)
     *
     * @return 실행 결과. 잠금을 얻지 못했으면 null
     */
    fun executeGuarded(options: CliOptions): TickResult? {
        val lock = lockRepo.tryAdvisoryLock()
        if (lock == null) {
            warnOnce("다른 인스턴스가 판정 중입니다(advisory lock 점유). 이번 틱을 건너뜁니다.")
            return null
        }
        return lock.use { runTick(options) }
    }

    /**
     * 한 틱 — 수집 → 평가 → 발생 → 발송 → 승격.
     *
     * 조건 하나의 오류가 전체를 죽이지 않는다. 실패는 결과에 모아 두고 다음 조건으로 넘어가며,
     * 그런 틱은 PARTIAL 로 남는다.
     */
    fun runTick(options: CliOptions): TickResult {
        val startedAt = OffsetDateTime.now(options.timezone)
        val result = TickResult()

        if (!runRepo.isSchemaReady()) {
            log.error(
                "엔진이 쓸 표가 없습니다. API 프로젝트의 db/V35__alm_engine.sql 을 적용하십시오. " +
                    "(ax.tb_alm_cond_state · tb_alm_send_queue · tb_alm_eval_run · tb_met_metric_collect)",
            )
            result.errors += "V35 스키마 미적용"
            recordAgentRun(options, startedAt, OffsetDateTime.now(options.timezone), result)
            return result
        }

        // ① 수집 — dry-run은 계산 결과만 출력하고 적재하지 않습니다.
        run {
            runCatching { collectService.collect(startedAt, result, persist = !options.dryRun) }
                .onFailure {
                    result.errors += "수집 단계 실패: ${Throwables.describe(it)}"
                    log.error("수집 단계에서 오류가 발생했습니다.", it)
                }
        }

        // ② ③ 평가 · 발생
        val conditions = condRepo.findActive(options.condIds)
        result.condCnt = conditions.size
        conditions.forEach { cond ->
            runCatching {
                val breaches = evaluator.evaluate(cond, startedAt, result, persist = !options.dryRun)
                if (options.dryRun) {
                    breaches.forEach {
                        log.info("[DRY-RUN] 알림 대상 — 조건 '{}' 대상 {} / {}", cond.name, it.scopeKey, it.evidence)
                    }
                } else {
                    breaches.forEach { raiser.raise(it, startedAt, result) }
                }
            }.onFailure {
                result.errors += "조건 ${cond.condId}(${cond.name}) 판정 실패: ${Throwables.describe(it)}"
                log.error("조건 '{}' 판정 중 오류가 발생했습니다. 다음 조건을 계속합니다.", cond.name, it)
            }
        }
        if (!options.dryRun && conditions.isNotEmpty()) {
            runCatching { condRepo.touchLastEval(conditions.map { it.condId }, startedAt) }
        }

        // ④ 발송
        if (!options.dryRun) {
            runCatching { dispatcher.dispatch(startedAt, result) }
                .onFailure {
                    result.errors += "발송 단계 실패: ${Throwables.describe(it)}"
                    log.error("발송 단계에서 오류가 발생했습니다.", it)
                }
        }

        val endedAt = OffsetDateTime.now(options.timezone)
        recordRun(options, startedAt, endedAt, result)
        recordAgentRun(options, startedAt, endedAt, result)
        return result
    }

    /**
     * 실행 이력을 남긴다.
     *
     * **아무 일도 없던 틱은 남기지 않는다.** 1분 주기면 하루 1,440행이 쌓여 정작 봐야 할
     * 실행이 묻힌다. 대신 조용한 구간도 엔진이 살아 있다는 것을 보이도록
     * 설정한 주기(기본 60분)마다 요약 1행을 남긴다 — 이력이 끊기면 엔진이 죽은 것이다.
     */
    private fun recordRun(
        options: CliOptions,
        startedAt: OffsetDateTime,
        endedAt: OffsetDateTime,
        result: TickResult,
    ) {
        if (options.dryRun) {
            log.info("[DRY-RUN] {} (기록하지 않음)", result.summary())
            return
        }

        val quiet = result.isQuiet
        if (quiet && options.mode == CliOptions.Mode.SCHEDULED) {
            val last = runCatching { runRepo.lastRecordedAt() }.getOrNull()
            val due = last == null ||
                Duration.between(last, endedAt).toMinutes() >= props.engine.heartbeatMin
            if (!due) return
        }

        val runId = "ALM-" + startedAt.format(runIdFmt)
        runCatching {
            runRepo.record(
                runId = runId,
                startedAt = startedAt,
                endedAt = endedAt,
                state = if (quiet) RunState.OK else result.state,
                result = result,
                trigger = options.trigger,
                triggeredBy = options.user.takeIf { options.mode != CliOptions.Mode.SCHEDULED },
                hostName = hostName,
                engineVersion = version,
                message = result.message() ?: if (quiet) "변화 없음 (정기 확인)" else null,
            )
        }.onFailure { log.warn("실행 이력을 남기지 못했습니다: {}", it.message) }

        if (!quiet) log.info("틱 종료 — {}", result.summary())
    }

    /**
     * AI 통합 대시보드에 Agent ⑨(이상 알림) 실행 1행을 남긴다 (ax.tb_ai_agent_run).
     *
     * [recordRun] 이 남기는 tb_alm_eval_run 을 대신하지 않는다. 저쪽은 알림 전용 상세,
     * 이쪽은 9종 Agent 가 함께 쓰는 통합 요약이라 보는 화면이 다르다.
     *
     * **조용한 틱도 남긴다.** 상세 이력과 반대다. 화면은 Agent 마다 최신 1행만 보고
     * master 상태는 "최근 10분 안에 행이 있는가" 로 판정하므로, 조용하다고 건너뛰면
     * 1분마다 멀쩡히 도는 엔진이 화면에서는 그대로 IDLE 로 남는다 — 이 기록을 넣는 이유가 그것이다.
     * 대신 행이 한 틱에 하나뿐이고 화면이 최신 1행만 읽으므로 쌓여도 조회가 무거워지지 않는다.
     *
     * dry-run 은 남기지 않는다. 판정 상태조차 쓰지 않는 실행이 화면의 '최근 실행' 을
     * 바꿔 놓으면 "확인만 했다" 가 아니게 된다.
     */
    private fun recordAgentRun(
        options: CliOptions,
        startedAt: OffsetDateTime,
        endedAt: OffsetDateTime,
        result: TickResult,
    ) {
        if (options.dryRun) return
        agentRunRepo.record(
            state = result.agentState,
            throughput = result.throughput(),
            elapsedMs = Duration.between(startedAt, endedAt).toMillis().toInt(),
            message = result.message(),
        )
    }

    fun cancel() {
        handle?.cancel(false)
        handle = null
    }

    fun isRunning(): Boolean = running.get()

    /**
     * 종료 신호(SIGTERM)를 받았을 때 **진행 중인 틱을 끝까지 마치게 한다.**
     *
     * 스레드 풀에 `waitForTasksToCompleteOnShutdown` 을 켜는 것만으로는 소용이 없다.
     * Spring 은 빈을 의존 역순으로 파괴하는데, 그대로 두면 커넥션 풀이 틱보다 먼저 닫혀
     * 진행 중이던 판정이 그 자리에서 죽는다. 이 클래스는 Repository → DataSource 를
     * 의존하므로 파괴 순서상 DataSource 보다 **먼저** 여기에 들어온다.
     *
     * 발송 도중 끊겨도 대기열에 SENDING 으로 남아 다음 기동에서 회수된다 —
     * 알림이 사라지지는 않는다.
     */
    @PreDestroy
    fun awaitRunningTick() {
        cancel()

        if (!running.get()) {
            log.info("종료 신호 — 진행 중인 틱이 없습니다. 바로 종료합니다.")
            return
        }

        val waitSec = props.engine.shutdownWaitSec
        log.warn("종료 신호 — 틱이 진행 중입니다. 끝날 때까지 최대 {}초 기다립니다.", waitSec)

        val startedAt = System.nanoTime()
        val deadline = startedAt + Duration.ofSeconds(waitSec).toNanos()
        while (running.get() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn("종료 대기가 중단되었습니다. 진행 중인 틱을 남긴 채 종료합니다.")
                return
            }
        }

        if (running.get()) {
            log.error(
                "틱이 {}초 안에 끝나지 않아 기다리기를 멈춥니다. 발송 중이던 건은 다음 기동에서 " +
                    "회수됩니다. 이 규모가 정상이라면 alert.engine.shutdown-wait-sec 을 늘리십시오.",
                waitSec,
            )
        } else {
            log.info(
                "진행 중이던 틱이 끝났습니다 ({}초 대기). 종료합니다.",
                Duration.ofNanos(System.nanoTime() - startedAt).toSeconds(),
            )
        }
    }

    @Volatile
    private var lastWarning: String? = null

    /** 같은 사유가 반복될 때 로그를 한 번만 남긴다 */
    private fun warnOnce(message: String) {
        if (message == lastWarning) return
        lastWarning = message
        log.warn(message)
    }
}
