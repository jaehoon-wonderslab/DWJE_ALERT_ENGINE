package com.dwje.alert

import com.dwje.alert.cli.Usage
import com.dwje.alert.config.AlertProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/**
 * 이상 알림 발송 엔진.
 *
 * SY-04 [이상 알림 발송 조건 관리] 에서 등록한 조건(ax.tb_alm_cond)을 주기적으로 판정하고,
 * 임계를 넘으면 알림(ax.tb_alm_alert)을 만들어 수신 그룹에 발송한다.
 *
 * 한 틱에서 하는 일은 다섯 가지다.
 *   ① 수집   원천(mes·ax) → ax.tb_met_metric_value
 *   ② 평가   조건 × 대상 비교 + 지속 조건 판정 → ax.tb_alm_cond_state
 *   ③ 발생   유효 시간대·중복 억제 판정 → ax.tb_alm_alert + ax.tb_alm_send_queue
 *   ④ 발송   대기열 → 채널(메일·팝업) → ax.tb_alm_send_log
 *   ⑤ 승격   확인되지 않은 알림을 상위 그룹으로
 *
 * 인자 없이 기동하면 상주 모드로 뜨고 1분마다 위 파이프라인을 돈다.
 * 실행 옵션은 [com.dwje.alert.cli.CliOptions] 참조.
 *
 * DataSource 를 직접 정의하므로 Boot 의 자동 구성을 끈다 (접속 정보를 검증한 뒤 만든다).
 */
@SpringBootApplication(exclude = [DataSourceAutoConfiguration::class])
@EnableConfigurationProperties(AlertProperties::class)
class AlertEngineApplication

fun main(args: Array<String>) {
    // 도움말은 컨텍스트를 띄우기 전에 처리한다.
    // 접속 설정 검증이 @PostConstruct 에서 기동을 거부하므로, 설정이 없는 상태로
    // --help 를 부르면 도움말 대신 스택 트레이스가 나온다. 옵션을 알아보려는
    // 사람이 가장 먼저 부르는 것이 --help 다. (이관 엔진에서 겪은 문제를 그대로 피한다)
    if (Usage.isHelpRequest(args)) {
        println(Usage.text())
        return
    }
    runApplication<AlertEngineApplication>(*args)
}
