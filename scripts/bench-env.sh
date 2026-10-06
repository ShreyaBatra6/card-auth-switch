#!/usr/bin/env bash
# Shared helpers for the benchmark scripts (Git Bash / Linux / macOS).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
SWITCH_JAR="$ROOT/switch-server/target/switch-server-1.0.0-SNAPSHOT.jar"
LOADGEN_JAR="$ROOT/loadgen/target/loadgen-1.0.0-SNAPSHOT.jar"
# G1 with a 10 ms pause target: see docs/performance.md for why (and why not ZGC on a 4-core laptop).
SWITCH_JVM_OPTS="${SWITCH_JVM_OPTS:--Xms1g -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=10}"
LOADGEN_JVM_OPTS="${LOADGEN_JVM_OPTS:--Xmx1g -XX:MaxGCPauseMillis=10}"
RESULTS="$ROOT/docs/results"
mkdir -p "$RESULTS"

start_switch() {
  "$JAVA" $SWITCH_JVM_OPTS -jar "$SWITCH_JAR" > "$RESULTS/switch.log" 2>&1 &
  SWITCH_PID=$!
  trap 'kill $SWITCH_PID 2>/dev/null || true' EXIT
  for _ in $(seq 1 120); do
    grep -q "Started CardSwitchApplication" "$RESULTS/switch.log" 2>/dev/null && return 0
    sleep 0.5
  done
  echo "Switch did not start; see $RESULTS/switch.log" >&2
  exit 1
}

stop_switch() {
  kill "$SWITCH_PID" 2>/dev/null || true
  wait "$SWITCH_PID" 2>/dev/null || true
}

set_issuer() { # set_issuer <id> <json>
  curl -s -o /dev/null -X PUT -H "Content-Type: application/json" -d "$2" "http://127.0.0.1:8080/admin/issuers/$1/simulator"
}

loadgen() {
  "$JAVA" $LOADGEN_JVM_OPTS -jar "$LOADGEN_JAR" "$@"
}
