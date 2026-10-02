# 작업 요청 — 틱 실행을 `ax.tb_ai_agent_run` 에도 남겨 주세요

요청일 2026-09-22 · 요청 출처 : DB/API 정합성 점검 세션
대상 : **Alert_Engine**

---

## 무엇이 문제인가

`ax.tb_ai_agent` 의 ⑨번이 **"이상 알림 — 임계 초과 · 패턴 이상 감지 및 발송"** 입니다.
이 엔진이 하는 일과 정확히 같습니다. 그런데 엔진은 `tb_ai_agent_run` 에 아무것도 남기지 않습니다.

그 결과 **AI 통합 대시보드(활성 화면)** 에서 Agent ⑨ 가 영원히 `IDLE` 로 보입니다.
엔진이 1분마다 정상 동작해도 화면은 "한 번도 안 돈 것" 으로 표시합니다.

실측 (`GET /api/v1/dashboard/ai/agents`) :

```
master: {state: "OK", recentRunCnt: 0, avgElapsedMs: null}
① 비전 수집 IDLE  ② 데이터 분류 IDLE  ③ 불량 판정 IDLE
④ 원인 분석 IDLE  ⑤ 이력 추적  IDLE  ⑥ 보고서 생성 IDLE
⑦ 보안 필터링 IDLE ⑧ KG 구축   IDLE  ⑨ 이상 알림  IDLE   ← 엔진 담당
```

`master.state` 는 "최근 10분 안에 실행 행이 있는가" 로 판정합니다.
행이 아예 없으면 오류가 나도 **OK** 로 나옵니다 — 장애를 정상으로 보이게 하는 쪽이라 더 나쁩니다.

---

## 지금 엔진이 쓰는 표

```
INSERT : tb_alm_alert · tb_alm_cond_state · tb_alm_eval_run
         tb_alm_send_log · tb_alm_send_queue · tb_met_metric_value
UPDATE : tb_alm_alert · tb_alm_cond · tb_alm_cond_state
         tb_alm_send_queue · tb_met_metric_collect
```

`tb_ai_agent_run` 은 **참조 자체가 없습니다** (소스 전문 검색 확인).

즉 실행 이력이 두 갈래로 갈라져 있습니다.

| 표 | 성격 | 누가 쓰나 |
|---|---|---|
| `tb_alm_eval_run` | 알림 전용 **상세** (cond_cnt · raise_cnt · sent_cnt · duration_ms …) | 이 엔진 ✓ |
| `tb_ai_agent_run` | 9종 Agent **통합 요약** (state · throughput · elapsed) | 아무도 안 씀 ✗ |

`tb_alm_eval_run` 은 그대로 두시면 됩니다. 상세 기록은 그쪽이 맞습니다.
**요청은 "통합 요약 한 줄을 추가로 남겨 달라"** 는 것입니다.

---

## 요청 내용

틱이 끝날 때 `ax.tb_ai_agent_run` 에 1행을 넣어 주세요.

### 대상 표

```sql
ax.tb_ai_agent_run (
    run_id          bigint      -- 자동 채번
    agent_id        integer     -- ⑨ 의 agent_id
    run_at          timestamptz
    state_cd        varchar
    throughput_txt  varchar(50)
    elapsed_ms      integer
    message         varchar(500)
    err_flg         char(1)     -- 'Y'/'N'
)
```

### agent_id 를 코드에 박지 마세요

`agent_no` 로 찾는 편이 안전합니다. API 쪽도 같은 방식을 씁니다
(`QualityRepository.insertAgentRun`).

```sql
INSERT INTO ax.tb_ai_agent_run
       (agent_id, run_at, state_cd, throughput_txt, elapsed_ms, message, err_flg)
SELECT a.agent_id, now(), :stateCd, :throughput, :elapsedMs, :message, :errFlg
  FROM ax.tb_ai_agent a
 WHERE a.agent_no = '⑨';
```

### 값을 무엇으로 채울지

이미 틱 종료 로그에 찍고 있는 값을 그대로 쓰면 됩니다.

```
틱 종료 — 조건 1건 · 판정 1 · 수집 446 · 억제 1
```

| 컬럼 | 제안 |
|---|---|
| `state_cd` | 정상 종료 `DONE`, 실패 `ERROR` — 공통코드 확인 후 맞출 것 |
| `throughput_txt` | `조건 1 · 판정 1 · 수집 446 · 발송 0` 처럼 한 줄 요약 (50자 제한) |
| `elapsed_ms` | 틱 소요 시간. `tb_alm_eval_run.duration_ms` 와 같은 값 |
| `message` | 실패 시 사유. 정상이면 NULL (500자 제한) |
| `err_flg` | 실패 `Y`, 정상 `N` |

### 꼭 지켜 주셨으면 하는 것

- **`--dry-run` 일 때는 쓰지 마세요.** 지금 dry-run 이 판정 상태조차 안 쓰는 것과 같은 이유입니다.
- **이 INSERT 실패가 틱을 깨뜨리지 않게** 해 주세요. 부가 기록이지 본업이 아닙니다.
  실패하면 WARN 만 남기고 넘어가는 편이 낫습니다.
- **틱마다 1행**이면 충분합니다. 조건별로 넣지 마세요 — 화면은 "Agent 하나의 최근 실행" 만 봅니다.

---

## 확인 방법

```bash
./start.sh --profile=local --now      # 1회 실행

psql -U dwje_local -d dwjedb -c "
  SELECT a.agent_no, r.state_cd, r.run_at, r.throughput_txt, r.elapsed_ms, r.err_flg
    FROM ax.tb_ai_agent_run r JOIN ax.tb_ai_agent a ON a.agent_id = r.agent_id
   ORDER BY r.run_at DESC LIMIT 3;"
```

화면 쪽 확인 :

```bash
curl -s "http://localhost:8080/api/v1/dashboard/ai/agents" -H "Authorization: Bearer <토큰>"
# ⑨ 이상 알림 의 state 가 IDLE 이 아니고 last 에 시각이 들어오면 성공
```

---

## 참고

- 스키마는 **로컬 · 운영 서버 모두 V39 까지 적용**돼 있어 표가 이미 있습니다 (지금 0행).
- `tb_ai_agent` 9행은 이미 들어 있습니다. ⑨ 는 `agent_id = 9` 이지만 위처럼 `agent_no` 로 찾으시길 권합니다.
- API 쪽에는 별도로 "나머지 Agent 기록 지점 보강" 을 요청해 두었습니다
  (`API/docs/REQUEST_ai_agent_run_and_model_screens.md`). 중복되지 않게 ⑨ 만 맡아 주시면 됩니다.
