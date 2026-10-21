#!/usr/bin/env bash
# Один performance-тест:  ./scripts/run.sh <k6|jmeter> <baseline|load|stress>
# Перед запуском має працювати SUT на http://127.0.0.1:8080 (див. README.md).
set -u

TOOL=${1:-}
PROFILE=${2:-}
PORT=${PORT:-8080}   # порт SUT; інший можна задати так: PORT=8081 ./scripts/run.sh ...
cd "$(dirname "$0")/.."

# Профілі навантаження (ті самі значення, що й у k6/rental-scenario.js)
case "$PROFILE" in
  baseline) USERS=2;   RAMPUP=5;  HOLD=55 ;;
  load)     USERS=20;  RAMPUP=15; HOLD=75 ;;
  stress)   USERS=100; RAMPUP=30; HOLD=60 ;;
  *) echo "Використання: ./scripts/run.sh <k6|jmeter> <baseline|load|stress>"; exit 1 ;;
esac

OUT="results/${TOOL}-${PROFILE}-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
echo ">>> $TOOL, профіль $PROFILE: $USERS VU, ramp-up ${RAMPUP}s, утримання ${HOLD}s, SUT :$PORT -> $OUT"

case "$TOOL" in
  k6)
    K6_WEB_DASHBOARD=true \
    K6_WEB_DASHBOARD_PERIOD=2s \
    K6_WEB_DASHBOARD_EXPORT="$OUT/report.html" \
      k6 run -e PROFILE="$PROFILE" -e BASE_URL="http://127.0.0.1:$PORT" --summary-export "$OUT/summary.json" k6/rental-scenario.js 2>&1 | tee "$OUT/console.txt"
    ;;
  jmeter)
    jmeter -n -t jmeter/rental-scenario.jmx \
      -Jprofile="$PROFILE" -Jport="$PORT" -Jusers="$USERS" -Jrampup="$RAMPUP" -Jduration=$((RAMPUP + HOLD)) \
      -Jjmeter.reportgenerator.overall_granularity=5000 \
      -l "$OUT/results.jtl" -j "$OUT/jmeter.log" -e -o "$OUT/report" 2>&1 | tee "$OUT/console.txt"
    ;;
  *) echo "Невідомий інструмент: $TOOL (k6 або jmeter)"; exit 1 ;;
esac

python3 scripts/metrics.py "$OUT"
