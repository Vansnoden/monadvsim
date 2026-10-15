#!/usr/bin/env bash
# ============================================================================
# summarize_log.sh — quick trajectory summary for monadvsim run logs
#
# Usage:
#   ./summarize_log.sh                     # auto-pick newest results/logs/simulation_*.log
#   ./summarize_log.sh path/to/sim.log     # explicit log file
#
# Revision notes:
#   * Timestamp is normalised to contain no whitespace, so awk field splitting
#     no longer shifts every column by one.
#   * All field-separated operations force TAB as the separator.
#   * Growth-rate table now emits a final partial window at the end of the run
#     (previously the last window was dropped when the run length was not a
#     multiple of 100 ticks).
#   * Peak/trough oscillation check now measures only the plateau phase:
#     it finds the first tick where agents >= 80% of peak, then computes the
#     trough within that region. This stops the seed-expansion phase from
#     masquerading as oscillation (which produced spurious 5x warnings on
#     stable logistic-curve runs).
#   * Verdict thresholds accept a final tick >= 1495 as "reached 1500".
# ============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# 1. Locate the log
# ---------------------------------------------------------------------------
LOG="${1:-}"
if [[ -z "$LOG" ]]; then
    if [[ -d results/logs ]]; then
        LOG=$(ls -t results/logs/simulation_*.log 2>/dev/null | head -1 || true)
    fi
    if [[ -z "${LOG:-}" && -d logs ]]; then
        LOG=$(ls -t logs/simulation_*.log 2>/dev/null | head -1 || true)
    fi
fi

if [[ -z "${LOG:-}" || ! -f "$LOG" ]]; then
    echo "ERROR: no log file found. Pass one explicitly: $0 path/to/log" >&2
    exit 1
fi

echo "======================================================================"
echo "Log summary: $LOG"
echo "Size:        $(du -h "$LOG" | cut -f1)"
echo "Lines:       $(wc -l < "$LOG")"
echo "======================================================================"

# ---------------------------------------------------------------------------
# 2. Extract [Monitor] series to a TAB-separated file:
#    col1=timestamp(no-space) col2=tick col3=agents col4=avgTickMs
#    Multiple monitor lines per tick are deduplicated: keep the last one.
# ---------------------------------------------------------------------------
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT

grep '\[Monitor\]' "$LOG" \
| sed -E 's/^\[([0-9-]+) ([0-9:]+)\].*Tick: ([0-9]+) \| Agents: ([0-9]+) \| Avg Tick Time: ([0-9.]+) ms.*/\1T\2\t\3\t\4\t\5/' \
| awk -F'\t' 'NF==4 { last[$2] = $0 } END { for (t in last) print last[t] }' \
| sort -t$'\t' -k2,2n > "$TMP"

if [[ ! -s "$TMP" ]]; then
    echo
    echo "No [Monitor] lines found in $LOG"
    exit 0
fi

FIRST_TICK=$(head -1 "$TMP" | cut -f2)
LAST_TICK=$(tail -1 "$TMP" | cut -f2)
FIRST_AGENTS=$(head -1 "$TMP" | cut -f3)
LAST_AGENTS=$(tail -1 "$TMP" | cut -f3)
LAST_MS=$(tail -1 "$TMP" | cut -f4)

# ---------------------------------------------------------------------------
# 3. Headline numbers
# ---------------------------------------------------------------------------
echo
echo "--- HEADLINE ----------------------------------------------------------"
printf "  First logged tick:   %s  (agents: %s)\n" "$FIRST_TICK" "$FIRST_AGENTS"
printf "  Last  logged tick:   %s  (agents: %s)\n" "$LAST_TICK"  "$LAST_AGENTS"
printf "  Final Avg Tick Time: %s ms\n" "$LAST_MS"

PEAK_LINE=$(sort -t$'\t' -k3,3n "$TMP" | tail -1)
PEAK_TICK=$(echo "$PEAK_LINE" | cut -f2)
PEAK_AGENTS=$(echo "$PEAK_LINE" | cut -f3)
printf "  Peak agents:         %s  (at tick %s)\n" "$PEAK_AGENTS" "$PEAK_TICK"

if [[ "$FIRST_AGENTS" -gt 0 ]]; then
    RATIO=$(awk -v a="$LAST_AGENTS" -v b="$FIRST_AGENTS" 'BEGIN{printf "%.2f", a/b}')
    printf "  Final / initial:     %sx\n" "$RATIO"
fi

# ---------------------------------------------------------------------------
# 4. Milestones — first tick where agent count crosses each threshold
# ---------------------------------------------------------------------------
echo
echo "--- MILESTONES (first tick reaching each agent count) -----------------"
for T in 3000 5000 10000 15000 25000 40000 60000 100000 150000; do
    HIT=$(awk -F'\t' -v t="$T" '$3 >= t { print $2"  (agents="$3")"; exit }' "$TMP")
    if [[ -n "$HIT" ]]; then
        printf "  >= %6d  tick %s\n" "$T" "$HIT"
    else
        printf "  >= %6d  never reached\n" "$T"
    fi
done

# ---------------------------------------------------------------------------
# 5. Coarse trajectory — every ~20th row
# ---------------------------------------------------------------------------
echo
echo "--- TRAJECTORY (every ~20th monitor row) ------------------------------"
printf "  %-22s %8s %10s %10s\n" "TIME" "TICK" "AGENTS" "MS"
awk -F'\t' 'NR % 20 == 1 {
    ts = $1; gsub(/T/, " ", ts);
    printf "  %-22s %8s %10s %10s\n", ts, $2, $3, $4
}' "$TMP"

# ---------------------------------------------------------------------------
# 6. Growth rate per 100-tick window, plus a final partial window
# ---------------------------------------------------------------------------
echo
echo "--- GROWTH RATE (agents per 100-tick window) --------------------------"
awk -F'\t' \
    -v last_tick="$LAST_TICK" \
    -v last_agents="$LAST_AGENTS" '
    { tick = $2; agents = $3 }
    tick >= next_win {
        if (prev_tick != "") {
            dt = tick - prev_tick
            da = agents - prev_agents
            rate = (dt > 0) ? da * 100.0 / dt : 0
            printf "  tick %5d -> %5d   agents %8d -> %8d   (%+d, %+.1f per 100t)\n",
                   prev_tick, tick, prev_agents, agents, da, rate
        }
        prev_tick   = tick
        prev_agents = agents
        next_win   += 100
    }
    END {
        if (prev_tick != "" && prev_tick != last_tick) {
            dt = last_tick - prev_tick
            da = last_agents - prev_agents
            rate = (dt > 0) ? da * 100.0 / dt : 0
            printf "  tick %5d -> %5d   agents %8d -> %8d   (%+d, %+.1f per 100t)\n",
                   prev_tick, last_tick, prev_agents, last_agents, da, rate
        }
    }
' "$TMP"

# ---------------------------------------------------------------------------
# 7. Tick-time progression — every ~40th row
# ---------------------------------------------------------------------------
echo
echo "--- TICK TIME PROGRESSION ---------------------------------------------"
printf "  %-22s %8s %12s\n" "TIME" "TICK" "MS"
awk -F'\t' 'NR % 40 == 1 {
    ts = $1; gsub(/T/, " ", ts);
    printf "  %-22s %8s %12s\n", ts, $2, $4
}' "$TMP"

# ---------------------------------------------------------------------------
# 8. Health checks
# ---------------------------------------------------------------------------
echo
echo "--- HEALTH CHECKS -----------------------------------------------------"
printf "  Mosquitoes layer timeouts:      %s\n" "$(grep -c 'Layer Mosquitoes timed out' "$LOG" || true)"
printf "  WARNING lines:                  %s\n" "$(grep -c '\[WARNING\]' "$LOG" || true)"
printf "  SEVERE  lines:                  %s\n" "$(grep -c '\[SEVERE\]' "$LOG" || true)"
printf "  Export queue overflow events:   %s\n" "$(grep -c 'Too many queued exports' "$LOG" || true)"
printf "  OutOfMemory / GC overhead msgs: %s\n" "$(grep -c 'OutOfMemory\|GC overhead' "$LOG" || true)"
printf "  Rejected tasks:                 %s\n" "$(grep -c 'Task rejected' "$LOG" || true)"

# ---------------------------------------------------------------------------
# 9. Verdict — thresholds from the run brief
# ---------------------------------------------------------------------------
echo
echo "--- VERDICT -----------------------------------------------------------"
VERDICT_OK=1

if [[ "$LAST_TICK" -ge 1495 && "$LAST_AGENTS" -lt 3000 ]]; then
    echo "  WARN: agents still < 3000 at tick $LAST_TICK — fecundity likely too low."
    VERDICT_OK=0
elif [[ "$LAST_TICK" -ge 1495 && "$LAST_AGENTS" -gt 60000 ]]; then
    echo "  WARN: agents > 60000 at tick $LAST_TICK — fecundity still too high."
    VERDICT_OK=0
elif [[ "$LAST_TICK" -lt 1495 ]]; then
    echo "  INFO: run did not reach tick 1500 (stopped at $LAST_TICK)."
fi

if awk -v ms="$LAST_MS" 'BEGIN { exit (ms > 8000) ? 0 : 1 }'; then
    echo "  WARN: final Avg Tick Time > 8000 ms — population approaching JVM limits."
    VERDICT_OK=0
fi

# Oscillation check — only measure the plateau region. Find the first tick
# where agents >= 80% of peak; from that point onward, compute trough and
# plateau-peak. This excludes the seed-expansion phase, which is not
# oscillation. If peak/trough within the plateau exceeds 5x, that indicates
# real ringing rather than logistic growth.
if [[ "$PEAK_AGENTS" -gt 0 ]]; then
    PLATEAU_START=$(awk -F'\t' -v p="$PEAK_AGENTS" '
        BEGIN { th = p * 0.8 }
        $3 >= th { print $2; exit }
    ' "$TMP")

    if [[ -n "${PLATEAU_START:-}" ]]; then
        TROUGH=$(awk -F'\t' -v ts="$PLATEAU_START" \
            '$2 >= ts { print $3 }' "$TMP" | sort -n | head -1 || true)
        PLATEAU_PEAK=$(awk -F'\t' -v ts="$PLATEAU_START" \
            '$2 >= ts { print $3 }' "$TMP" | sort -n | tail -1 || true)

        if [[ -n "${TROUGH:-}" && "$TROUGH" -gt 0 ]]; then
            OSC=$(awk -v p="$PLATEAU_PEAK" -v t="$TROUGH" \
                'BEGIN{printf "%.2f", p/t}')
            if awk -v o="$OSC" 'BEGIN { exit (o > 5) ? 0 : 1 }'; then
                echo "  WARN: plateau oscillation peak/trough = ${OSC}x (> 5, from tick $PLATEAU_START) — density brake may be too weak."
                VERDICT_OK=0
            fi
        fi
    fi
fi

if [[ "$VERDICT_OK" -eq 1 ]]; then
    echo "  OK: trajectory looks within bounds."
fi
echo "======================================================================"