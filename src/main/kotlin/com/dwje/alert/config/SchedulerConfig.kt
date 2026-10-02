package com.dwje.alert.config

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * 엔진 틱 스레드 풀.
 *
 * 판정은 순차 실행이므로 스레드 1개면 충분하다. 상주 모드에서 이 스레드가 프로세스를 유지한다.
 *
 * 종료 시 진행 중인 틱을 기다리는 **본체는 여기가 아니라**
 * [com.dwje.alert.service.AlertEngineScheduler.awaitRunningTick] 다.
 * 여기서만 기다리게 하면 커넥션 풀이 먼저 닫혀 틱이 그 자리에서 죽는다 —
 * 빈 파괴 순서상 DataSource 가 이 풀보다 늦게 닫힌다는 보장이 없기 때문이다.
 * 아래 설정은 그 대기가 끝난 뒤 남은 작업을 정리하는 2차 안전망이다.
 */
@Configuration
class SchedulerConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun taskScheduler(): TaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("alert-engine-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
        setErrorHandler { t ->
            log.error("틱에서 처리되지 않은 오류가 발생했습니다. 스케줄은 유지됩니다.", t)
        }
        initialize()
    }
}
