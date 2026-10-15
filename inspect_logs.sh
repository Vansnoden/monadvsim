#!/bin/bash

LOG=$(ls -t results/logs/simulation_*.log | head -1)

echo "=== first 5 ticks ==="
grep "\[Monitor\]" "$LOG" | head -5

echo "=== every ~500 agents ==="
grep "\[Monitor\]" "$LOG" | awk 'NR % 20 == 1'

echo "=== last 5 ticks ==="
grep "\[Monitor\]" "$LOG" | tail -5

echo "=== timeout count ==="
grep -c "Layer Mosquitoes timed out" "$LOG"
