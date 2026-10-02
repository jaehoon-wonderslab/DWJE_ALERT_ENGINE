package com.dwje.alert.service.collector

import com.dwje.alert.model.CollectDef
import com.dwje.alert.model.MetricPoint
import java.time.OffsetDateTime

/**
 * 지표 수집기.
 *
 * 지표 하나를 원천에서 계산해 [MetricPoint] 목록으로 돌려준다.
 * 적재(ax.tb_met_metric_value)와 판정은 엔진이 하고, 구현체는 **계산만** 한다.
 *
 * 새 지표를 붙이는 절차는 세 단계다.
 *   1. SY-13 [지표 측정 데이터 관리] 에서 지표를 등록한다 (metric_cd · 기준/주의/위험값)
 *   2. 이 인터페이스를 구현하고 [metricCd] 를 그 코드와 맞춘다
 *   3. ax.tb_met_metric_collect 에 행을 넣고 use_flg='Y' 로 켠다
 *
 * 2번이 배포를 요구하는 것이 지금 설계의 한계다. 집계 SQL 을 표에 넣어 화면에서 편집하면
 * 배포 없이 지표를 늘릴 수 있지만, 그 표에 쓰기 권한을 가진 사람이 DB 에서 임의 SQL 을
 * 돌릴 수 있게 된다. 읽기 전용 롤 분리가 선행되어야 해서 2단계로 미뤘다 (V35 주석).
 */
interface MetricCollector {

    /** 어느 지표를 채우는가. ax.tb_met_metric_std.metric_cd 와 같아야 한다 */
    val metricCd: String

    /**
     * [from] 이상 [to] 미만 구간의 값을 계산한다.
     *
     * 구간을 벗어난 값을 돌려주면 안 된다 — 엔진은 받은 것을 그대로 적재하므로,
     * 겹치는 값을 주면 같은 시점이 두 번 쌓여 이동평균이 틀어진다.
     */
    fun collect(def: CollectDef, from: OffsetDateTime, to: OffsetDateTime): List<MetricPoint>
}
