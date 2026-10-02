#!/usr/bin/env bash
# DB와 발송 대기열을 사용하지 않고 지정된 시험 수신자에게 메일 한 통을 보냅니다.
set -euo pipefail
cd "$(dirname "$0")"
ALERT_SMTP_DELIVERY_TEST=1 ./gradlew test --tests com.dwje.alert.SmtpDeliveryTest --rerun-tasks
