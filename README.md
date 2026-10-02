# 이상 알림 발송 엔진 (alert-engine)

SY-04 [이상 알림 발송 조건 관리] 화면에서 등록한 조건을 **실제로 판정하고 발송하는** 상주 엔진.

화면은 「언제 · 무엇을 기준으로」를 정의하고, 수신자 관리(SY-05)는 「누구에게 · 어떤 연락처로」를 정한다.
그 둘을 실제로 돌리는 주체가 없어서 조건을 등록해도 아무 일도 일어나지 않았다. 이 엔진이 그 자리를 맡는다.

- 설계서 : `../API/docs/ALERT_ENGINE_DESIGN_20260916.md`
- 스키마 : `../API/src/main/resources/db/V35__alm_engine.sql` (**먼저 적용해야 한다**)
- 만든 방식 : `../MES_migration_engine` 과 같은 구성 (Spring Boot 배치 · JAR · start/stop 스크립트)

---

## 1. 한 번의 실행에서 하는 일

```
 mes.tb_pop_label_hist   ┌─ 1분 틱 (advisory lock 으로 한 곳에서만) ──────────────────┐
 ax.tb_prod_downtime ──▶ │ ① 수집 ─▶ ax.tb_met_metric_value                          │
                         │      │                                                    │
                         │      ▼                                                    │
                         │ ② 평가 ─ 임계 비교 + 지속 조건 → ax.tb_alm_cond_state      │
                         │      │                                                    │
                         │      ▼                                                    │
                         │ ③ 발생 ─ 중복 억제 · 유효 시간대 판정                       │
                         │      │   ├ 통과 → tb_alm_alert + tb_alm_send_queue         │
                         │      │   ├ 억제 → 기존 알림 hit_cnt++ · send_log SUPPRESSED │
                         │      │   └ 제외 → send_log SKIPPED                         │
                         │      ▼                                                    │
                         │ ④ 발송 ─ 대기열(SKIP LOCKED) → 메일·팝업 → tb_alm_send_log │
                         │      ▼                                                    │
                         │ ⑤ 승격 ─ 확인 안 된 알림을 상위 그룹으로                    │
                         └─ 실행 이력 → ax.tb_alm_eval_run · ax.tb_ai_agent_run ────┘
```

**아무 일도 없던 틱은 이력을 남기지 않는다.** 1분 주기면 하루 1,440행이 쌓여 정작 봐야 할 실행이 묻힌다.
대신 조용한 구간에도 1시간에 한 번은 요약 행을 남긴다 — 이력이 끊기면 엔진이 죽은 것이다.

### 실행 이력을 두 곳에 남기는 이유

| 표 | 성격 | 남기는 시점 |
|---|---|---|
| `ax.tb_alm_eval_run` | 알림 전용 **상세** — 조건·판정·발생·억제·발송 건수, 실행자, 호스트 | 일이 있었던 틱 (조용하면 1시간에 1행) |
| `ax.tb_ai_agent_run` | 9종 Agent **통합 요약** — AI 통합 대시보드의 `⑨ 이상 알림` 한 칸 | **모든 틱** (dry-run 제외) |

통합 요약은 조용한 틱도 남긴다. 화면은 Agent 마다 최신 1행만 보고 master 상태는 "최근 10분 안에
행이 있는가" 로 판정한다 — 조용하다고 건너뛰면 1분마다 멀쩡히 도는 엔진이 화면에서는 `IDLE` 로 남고,
장애가 나도 행이 없어 `OK` 로 보인다.

`⑨` 는 `agent_no` 로 찾는다 (`agent_id` 를 박지 않는다). 이 기록이 실패해도 틱은 계속된다 —
부가 기록이지 본업이 아니라서 WARN 한 줄만 남기고 넘어간다. 나머지 `①`~`⑧` 은 API 쪽이 채운다.

---

## 2. 사전 조건

| 항목 | 내용 |
|---|---|
| JDK | 21 이상 |
| DB | PostgreSQL — `ax`(조건·상태·알림) · `mes`(원천) 스키마 |
| 스키마 | **`V35__alm_engine.sql` 적용 필수.** 없으면 기동은 하되 아무 일도 하지 않고 무엇이 없는지 알린다 |
| 지표 | 판정할 지표가 `ax.tb_met_metric_collect` 에 `use_flg='Y'` 로 켜져 있어야 한다 |

```bash
# 스키마 적용 (로컬)
docker exec -e PGPASSWORD=dwje_local dwje-pg \
  psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V35__alm_engine.sql
```

---

## 3. 빠른 시작

```bash
# 1) 접속 설정 (택 1)
cp config/local.yml.example config/local.yml      # 파일로 두기 (.gitignore 대상)
cp config/engine.env.example config/engine.env    # 환경변수로 두기 — start.sh 가 읽는다

# 2) 빌드 — JAR + 설정 + 스크립트를 build/libs 에 모은다
./gradlew clean dist

# 3) 확인 — 아무것도 바꾸지 않고 판정만 해 본다
./start.sh --profile=local --now --dry-run

# 4) 상주 기동 (백그라운드)
./start.sh --profile=local

# 5) 상태 · 중지
./status.sh
./stop.sh
```

운영 서버는 `--profile=prod` 로 띄운다. 프로파일을 빠뜨리면 메일 모드가 `LOG` 로 떨어져
**아무도 알림을 받지 못하는데 로그에는 정상으로 보인다.** `start.sh` 의 기본값이 `prod` 인 이유다.

---

## 4. 실행 옵션

| 옵션 | 뜻 |
|---|---|
| (없음) | 상주 모드. 설정 주기(기본 1분)로 판정 |
| `--interval=30s` | 상주 모드, 벽시계 기준 주기 (s 초 · m 분 · h 시간). 60·24를 나누어떨어지는 값만 |
| `--cron="0 * * * * *"` | 상주 모드, Spring cron 6필드 직접 지정 |
| `--now` | 즉시 1회 실행 후 종료 |
| `--cond=12,15` | 조건 번호만 판정 |
| `--dry-run` | 판정만. 알림·발송은 물론 **판정 상태도 쓰지 않는다** |
| `--user=<id>` | 실행 이력에 남길 실행자 |
| `--profile=local\|prod` | (start.sh 전용) 프로파일 |
| `--help` | 도움말. 접속 설정 없이도 볼 수 있다 |

`--dry-run` 이 상태까지 안 쓰는 이유 — 상태를 쓰면 다음 실제 판정의 연속 시간·억제 창이 달라져
「확인만 했다」가 아니게 된다.

---

## 5. 판정 규칙

숫자는 전부 **공통코드의 `attr1`/`attr2`** 에서 읽는다 (V35 가 채운다). 코드에 박지 않았다 —
중복 억제 창을 45분으로 바꾸는 일이 배포가 되면 안 되기 때문이다.

| 규칙 | 근거 | 동작 |
|---|---|---|
| 비교 | `ALM_OP.attr1` | `>=` `>` `<=` `<` `=`. `RATE` 는 직전 값 대비 변화율(%) |
| 지속 | `ALM_DURATION.attr1/attr2` | `CONT` 연속 N초 · `AVG` 구간 이동평균 · `CLOSE` 일 마감 1회 |
| 중복 억제 | `ALM_DEDUP.attr1` | 분 단위. `-1` 은 달력일 1회. 억제돼도 기존 알림의 `hit_cnt` 는 오른다 |
| 유효 시간대 | `ALM_WINDOW.attr1/attr2` | 밖이면 알림은 남기고 발송만 건너뛴다(`SKIPPED`) |
| 야간 | `alert.night.from/to` | 수신자·그룹이 야간 미수신이면 그 사람만 건너뛴다 |

상태 전이는 `NORMAL → PENDING → BREACH` 다. `PENDING` 은 「임계는 넘었지만 10분 연속을 아직 못 채웠다」는 뜻이고,
중간에 한 번이라도 정상이면 연속은 처음부터 다시 센다.

**값이 낡으면 판정하지 않는다.** 마지막 수집이 `평가주기 × 3`(최소 10분)보다 오래됐으면 그 조건을 건너뛰고 경고만 남긴다.
멈춘 수집의 낡은 값으로 판정하면 이미 끝난 이상이 계속 나가거나 진짜 이상을 정상으로 본다.

---

## 6. 지표 수집기

지금 붙어 있는 것은 둘이다.

| 지표 코드 | 계산 | 원천 |
|---|---|---|
| `EQPT_UPTIME_RATE` | (조업시간 − 비가동시간) ÷ 조업시간 × 100 | `ax.tb_prod_downtime` + 운영 중인 설비 목록 |
| `PROC_DEFECT_RATE` | 불량 ÷ (양품 + 불량) × 100 | `mes.tb_pop_label_hist` |

`PROC_DEFECT_RATE` 는 아직 `ax.tb_met_metric_std` 에 **등록돼 있지 않다.** 지표 등록은 SY-13 화면의 일이라
엔진이 마음대로 만들지 않는다. 화면에서 등록하고 `tb_met_metric_collect` 를 켜면 그때부터 이 수집기가 붙는다.

### 대상 설비를 고르는 방식

설비 마스터에 1,490대가 있지만 실제로 도는 것은 그중 일부다(최근 24시간 기준 약 550대).
전부 수집하면 5분 주기에서 하루 40만 행이 쌓이는데 대부분은 몇 년째 안 쓰는 설비의 "가동률 100%" 다.
그래서 **최근 24시간 생산 실적이 있는 설비 + 지금 비가동이 걸린 설비** 를 대상으로 한다.
두 번째 항이 없으면 아침부터 멈춰 선 설비가 대상에서 빠져, 정작 그 설비에서 알림이 나지 않는다.

### 새 지표를 붙이는 절차

1. SY-13 에서 지표를 등록한다 (`metric_cd` · 기준/주의/위험값)
2. `MetricCollector` 를 구현하고 `metricCd` 를 그 코드와 맞춘다 → **배포 필요**
3. `ax.tb_met_metric_collect` 에 행을 넣고 `use_flg='Y'` 로 켠다

2번이 배포를 요구하는 것이 지금의 한계다. 집계 SQL 을 표(`sql_text`)에 넣으면 배포 없이 늘릴 수 있지만,
그 표에 쓰기 권한을 가진 사람이 DB 에서 임의 SQL 을 돌릴 수 있게 된다. 읽기 전용 롤 분리가 선행되어야 해서 2단계로 미뤘다.

---

## 7. 발송 채널

| 채널 | 상태 |
|---|---|
| `MAIL` | `LOG`(내용을 로그로) / `SMTP`(실제 발송). 프로파일로 고른다 |
| `POPUP` | 웹 화면이 `tb_alm_alert` 를 읽어 띄운다. 엔진은 발송 기록만 남긴다 |
| `SMS` · `MSG` | **연동처 없음.** 보낸 척하지 않고 바로 실패로 기록한다 |

SMS 를 성공으로 처리하지 않는 이유 — "SMS 로 알렸다"는 기록만 남고 아무도 받지 못한 상태가
알림 시스템에서 가장 나쁜 실패다. 연동이 정해지면 `service/channel/UnconfiguredChannels.kt` 를 갈아 끼우면 된다.

### 재시도

실패하면 `1분 → 5분 → 15분 → 30분 → 60분` 으로 밀며 최대 5회. 넘기면 `DEAD` 로 두고 더 보지 않는다.
발송 도중 엔진이 죽으면 그 건은 `SENDING` 으로 남고 5분 뒤 다음 기동이 회수한다 — 알림이 사라지지는 않는다.

---

## 8. 설정

`src/main/resources/application.yml` 에 전부 주석과 함께 있다. 자주 건드리는 것만:

| 키 | 기본 | 뜻 |
|---|---|---|
| `alert.schedule.interval` | `1m` | 틱 주기 |
| `alert.engine.advisory-lock-key` | `480401` | 다중 인스턴스 방지 키 (이관 엔진 8260829 와 달라야 한다) |
| `alert.engine.stale-factor` | `3` | 지표가 이 배수만큼 낡으면 판정하지 않는다 |
| `alert.engine.heartbeat-min` | `60` | 조용한 구간에 요약 이력을 남기는 주기(분) |
| `alert.collect.max-points-per-run` | `5000` | 한 번에 적재할 지표 값 상한 |
| `alert.dispatch.max-try` | `5` | 발송 재시도 횟수 |
| `alert.night.from/to` | `22:00`/`06:00` | 야간 구간 |
| `alert.message.mail-mode` | `LOG` | prod 프로파일은 `SMTP` |
| `alert.message.mask-by-recipient` | `true` | 수신자 권한에 따라 본문 값을 가린다 |

접속 정보는 **코드·yml 에 두지 않는다.** 환경변수 3개(`ALERT_DB_URL` · `ALERT_DB_USERNAME` · `ALERT_DB_PASSWORD`)
또는 `config/local.yml`. 없으면 기동을 거부하고 설정 방법을 안내한다.

---

## 9. 운영

```bash
./start.sh --profile=prod          # 상주 기동 (백그라운드, run/alert-engine.pid)
./status.sh                        # 프로세스 + 최근 실행 이력
./stop.sh                          # 안전 종료 (진행 중인 틱을 기다린다, 최대 180초)
./stop.sh --force                  # 최후 수단
tail -f logs/alert.log             # 전체 로그 (30일 보존)
tail -f logs/alert-error.log       # WARN 이상만 (90일 보존)
```

### 무엇이 잘못됐는지 보는 곳

| 증상 | 볼 곳 |
|---|---|
| 알림이 안 온다 | `ax.tb_alm_send_log` — `SUPPRESSED`(중복 억제) · `SKIPPED`(시간대·야간) · `FAIL`(연락처 없음 등) |
| 판정이 안 된다 | `ax.tb_alm_cond_state` 의 `last_eval_at` · `state_cd`, 그리고 `logs/alert-error.log` |
| 값이 안 쌓인다 | `ax.tb_met_metric_collect` 의 `use_flg` · `last_run_at` · `last_error` |
| 엔진이 도는가 | `ax.tb_alm_eval_run` — 1시간 넘게 행이 없으면 죽은 것이다 |
| 화면의 `⑨ 이상 알림` 이 IDLE 이다 | `ax.tb_ai_agent_run` — 틱마다 1행이 들어와야 한다. 안 들어오면 `logs/alert-error.log` 의 `AgentRunRepository` WARN |

### systemd (운영 서버)

```ini
[Unit]
Description=DWJE 이상 알림 발송 엔진
After=network.target

[Service]
Type=simple
User=dwje
WorkingDirectory=/opt/dwje/alert-engine
EnvironmentFile=/etc/default/alert-engine
ExecStart=/usr/bin/java -Xms256m -Xmx1g -Duser.timezone=Asia/Seoul -Dfile.encoding=UTF-8 \
          -jar /opt/dwje/alert-engine/alert-engine.jar --spring.profiles.active=prod
Restart=on-failure
TimeoutStopSec=200

[Install]
WantedBy=multi-user.target
```

`TimeoutStopSec` 은 `alert.engine.shutdown-wait-sec`(120) 보다 커야 한다. 작으면 엔진이 틱을 마무리하는 중에 systemd 가 먼저 죽인다.

---

## 10. 다중 인스턴스

- **판정(②③)은 한 곳에서만** — PostgreSQL advisory lock. 겹치면 같은 알림이 두 번 나가거나 연속 시간이 서로 덮인다.
- **발송(④)은 나눠 가진다** — `FOR UPDATE SKIP LOCKED`. 워커를 늘려도 같은 건을 두 번 보내지 않는다.
- 같은 (알림 · 수신자 · 채널 · 승격단계)는 대기열의 유니크 인덱스가 한 번만 들어가게 막는다. 엔진이 죽었다 떠도 중복 발송이 없다.

---

## 11. 아직 안 되는 것 · 결정이 필요한 것

| 항목 | 지금 | 필요한 결정 |
|---|---|---|
| SMS · 메신저 | 실패로 기록 | 연동처 확정 |
| 지표 수집 SQL 편집 | 코드 구현만(BUILTIN) | 읽기 전용 롤 분리 후 `sql_text` 개방 여부 |
| 일 마감(`DAY_CLOSE`) 기준 시각 | 조건의 `window_time`, 없으면 08:00 | 이관 야간 배치 완료 시점에 맞출지 |
| 복구(해제) 알림 | 보내지 않음 (`resolved_at` 만 기록) | 정상 복귀도 통보할지 |
| `tb_met_metric_value` 파티션 | 없음 | 값이 쌓이기 시작하면 월 파티션(V36 예정) |
| 당직 대리 수신 | 없음 (V36 에서 당번 표 제거) | 부재자 대신 받을 사람을 둘지 |

---

## 12. 소스 구성

```
src/main/kotlin/com/dwje/alert/
├─ AlertEngineApplication.kt      기동 (도움말은 컨텍스트 밖에서 처리)
├─ cli/                           옵션 해석 · 도움말 · 진입점
├─ config/                        설정 · DataSource · 스케줄러 풀 · 기동 검증
├─ model/                         조건 · 지표 · 상태 · 발송 · 틱 집계
├─ repository/                    ax 스키마 질의 (NamedParameterJdbcTemplate)
└─ service/
   ├─ AlertEngineScheduler.kt     틱 · advisory lock · 실행 이력 · 안전 종료
   ├─ MetricCollectService.kt     ① 수집
   ├─ ConditionEvaluator.kt       ② 평가 (임계 · 지속 · 상태 전이)
   ├─ AlertRaiser.kt              ③ 발생 (억제 · 시간대 · 대기열 적재)
   ├─ SendDispatcher.kt           ④ 발송 (SKIP LOCKED · 재시도)
   ├─ EscalationRunner.kt         ⑤ 승격
   ├─ MessageRenderer.kt          문구 치환 · 수신자별 마스킹
   ├─ collector/                  지표 수집기
   └─ channel/                    발송 채널
```
