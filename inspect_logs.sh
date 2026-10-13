#!/bin/bash

LOG=$(ls -t results/logs/simulation_*.log | head -1)

echo "=== transitions (expect all three > 50 now) ==="
grep -cE "LARVA → PUPA"    "$LOG"
grep -cE "PUPA → ADULT"    "$LOG"
grep -cE "\[TANK\] Hatched" "$LOG"

echo "=== ticks and progress ==="
grep -E "\[Monitor\]" "$LOG" | head -10
grep -E "\[Monitor\]" "$LOG" | tail -10

echo "=== snapshots ==="
grep -E "Processed:" "$LOG"

echo "=== final outcome ==="
grep -E "reached total ticks|Thread still alive|Simulation completed" "$LOG"
