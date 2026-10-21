#!/usr/bin/env bash
# Усі шість запусків підряд (k6 і JMeter × baseline, load, stress), потім зведена таблиця. ~10 хвилин.
set -u
cd "$(dirname "$0")/.."

for tool in k6 jmeter; do
  for profile in baseline load stress; do
    ./scripts/run.sh "$tool" "$profile"
    sleep 10   # коротка пауза, щоб SUT «видихнув» між запусками
  done
done

echo
echo "=== Зведена таблиця ==="
python3 scripts/metrics.py
