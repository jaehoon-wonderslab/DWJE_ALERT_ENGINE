package com.dwje.alert.model

/**
 * 엔진이 쓰는 코드값.
 *
 * 값 자체는 공통코드(ax.tb_sys_code)에 있고 여기 있는 것은 **엔진이 반드시 아는 것** 뿐이다.
 * 판정에 쓰는 숫자(억제 30분·연속 600초·시간대 08:00)는 여기 넣지 않는다 —
 * 그 값들은 공통코드의 attr1/attr2 에서 읽는다 (V35). 코드를 고쳐야 바뀌는 값과
 * 운영 중에 바꿀 값을 섞지 않으려는 것이다.
 */

/** 엔진 실행 결과 — 공통코드 ALM_RUN_STATE */
enum class RunState { RUNNING, OK, PARTIAL, FAIL }

/** 엔진 실행 주체 — 공통코드 ALM_RUN_TRIGGER */
enum class RunTrigger { BATCH, MANUAL, TEST }

/** 조건 × 대상의 판정 상태 — 공통코드 ALM_COND_STATE */
enum class CondStateCd {
    /** 임계 안쪽 */
    NORMAL,

    /** 임계는 넘었으나 지속 조건(예: 10분 연속)을 아직 못 채웠다 */
    PENDING,

    /** 지속 조건까지 충족했다 */
    BREACH,
}

/** 발송 대기열 상태 — 공통코드 ALM_QUEUE_STATE */
enum class QueueState { PENDING, SENDING, DONE, FAIL, DEAD }

/** 발송 결과 — 공통코드 ALM_SEND_RESULT */
enum class SendResult {
    SENT,

    FAIL,

    /** 중복 억제 창에 걸려 보내지 않음 */
    SUPPRESSED,

    /** 조건·그룹의 유효 시간대 밖 */
    SKIPPED,
}

/** 지속 조건 판정 방식 — ALM_DURATION.attr2 */
enum class DurationKind {
    /** 연속 위반 시간으로 판정 */
    CONT,

    /** 구간 이동평균으로 판정 */
    AVG,

    /** 일 마감 시점에 1회 판정 */
    CLOSE;

    companion object {
        fun of(code: String?): DurationKind =
            entries.firstOrNull { it.name == code?.uppercase() } ?: CONT
    }
}

/**
 * 평가 단위 — 공통코드 ALM_SCOPE_DIM.
 * 조건 하나를 어느 단위로 쪼개 판정할지를 정한다. EQPT 면 설비마다 따로 터지고 따로 억제된다.
 */
enum class ScopeDim {
    NONE, EQPT, WC, ITEM, MOLD, PRODUCT;

    companion object {
        fun of(code: String?): ScopeDim =
            entries.firstOrNull { it.name == code?.uppercase() } ?: NONE
    }
}

/**
 * AI 통합 대시보드가 읽는 Agent 실행 상태 — 공통코드 AI_AGENT_STATE.
 *
 * 엔진 내부의 [RunState] 와 어휘가 다르다. 저쪽은 알림 전용 상세 이력(tb_alm_eval_run)의 것이고
 * 이쪽은 9종 Agent 가 함께 쓰는 것이라 PARTIAL 이 없다. 조건 하나가 실패한 틱은 ERROR 로 올린다 —
 * 부분 실패를 화면에 정상으로 보이게 하는 쪽이 더 나쁘다.
 *
 * RUNNING · IDLE · STOPPED 는 엔진이 쓰지 않는다. 틱이 끝난 뒤에 한 행을 남기므로 RUNNING 인
 * 순간이 없고, IDLE 은 행이 아예 없을 때 화면이 스스로 매기는 값이다.
 */
enum class AiAgentState { OK, RUNNING, IDLE, ERROR, STOPPED }
