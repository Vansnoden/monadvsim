#!/bin/bash

LOG=$(ls -t results/logs/simulation_*.log | head -1)

echo "=== census (first 5, last 5) ==="
grep "\[DIAG\]" "$LOG" | head -5
grep "\[DIAG\]" "$LOG" | tail -5

echo "=== lay attempts ==="
grep -c "\[LAY\]"    "$LOG"
grep -c "\[NOLAY\]"  "$LOG"

echo "=== hatches ==="
grep -c "\[TANK\] Hatched" "$LOG"

echo "=== LAY samples (first 3) ==="
grep "\[LAY\]" "$LOG" | head -3

echo "=== NOLAY samples (first 3) ==="
grep "\[NOLAY\]" "$LOG" | head -3
