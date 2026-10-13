#!/bin/bash

LOG=$(ls -t results/logs/simulation_*.log | head -1)

echo "=== tank counter sanity ==="
grep -cE "LARVA → PUPA"      "$LOG"
grep -cE "died as LARVA"     "$LOG"
grep -cE "\[TANK\] Hatched"  "$LOG"

echo "=== population trajectory ==="
grep "\[Monitor\]" "$LOG" | head -6
grep "\[Monitor\]" "$LOG" | tail -6
