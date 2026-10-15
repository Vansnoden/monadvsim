#!/bin/bash

LOG=results/logs/simulation_20261014_201405.log

# Most common SEVERE messages, deduped
grep '\[SEVERE\]' "$LOG" | sed -E 's/^\[[^]]+\] \[SEVERE\] //' | sort | uniq -c | sort -rn | head -20

# First occurrence — when did it start
grep -n '\[SEVERE\]' "$LOG" | head -5

# Are they clustered around specific ticks?
grep '\[SEVERE\]' "$LOG" | grep -oE 'Tick: [0-9]+' | head -20
