#!/usr/bin/env bash
# Reproduces docs/performance.md. Build first: ./mvnw package
# Usage: ./scripts/benchmark.sh [capacity] [realistic] [resilience]   (default: all three)
source "$(dirname "$0")/bench-env.sh"
SECTIONS="${*:-capacity realistic resilience}"
wants() { [[ " $SECTIONS " == *" $1 "* ]]; }

if wants capacity; then
echo "=== 1. Switch capacity: issuers answer instantly, so latency is the switch's own ==="
start_switch
for issuer in northwind harbor cedar; do set_issuer "$issuer" '{"medianLatencyMs":0,"p99LatencyMs":0}'; done
loadgen run --tps 2000,5000,10000,15000 --duration 30 --warmup 15 --connections 16 --io-threads 4 \
  --summary "$RESULTS/capacity.md" | tee "$RESULTS/capacity.log" | grep -vE '^\[ *[0-9]+s\]'
stop_switch

fi

if wants realistic; then
echo "=== 2. End to end with simulated issuer latency (medians 2/4/6 ms, p99 12/20/30 ms) ==="
start_switch
loadgen run --tps 1000,2000,5000 --duration 30 --warmup 15 --connections 16 --io-threads 4 --reversal-pct 1 \
  --summary "$RESULTS/realistic.md" | tee "$RESULTS/realistic.log" | grep -vE '^\[ *[0-9]+s\]'
stop_switch

fi

if wants resilience; then
echo "=== 3. Resilience: Cedar goes down at 30s and recovers at 60s, at 2,000 TPS ==="
start_switch
loadgen run --tps 2000 --duration 90 --warmup 15 --connections 16 --io-threads 4 \
  --chaos 30:cedar:available=false,60:cedar:available=true \
  --csv "$RESULTS/resilience.csv" --summary "$RESULTS/resilience.md" | tee "$RESULTS/resilience.log"
curl -s http://127.0.0.1:8080/admin/issuers > "$RESULTS/resilience-issuers.json"
stop_switch
fi
