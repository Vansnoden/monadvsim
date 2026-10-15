#!/usr/bin/env bash
# stop_and_merge.sh
#
# 1. Interrupts the running monadvsim process (SIGINT, then SIGKILL after grace).
# 2. Merges every snapshot_tick_*.csv in results/ into one merged file.
# 3. Writes a partial statistics file with the same layout as
#    App.saveFinalStatisticsToFile, plus a stage breakdown and inert-agent totals.
#
# Usage:
#   ./stop_and_merge.sh [results_dir] [grace_seconds]
#
# Flags:
#   KEEP_SNAPSHOTS=1 ./stop_and_merge.sh   # don't delete intermediate files

set -uo pipefail

RESULTS_DIR="${1:-results}"
GRACE_SECONDS="${2:-120}"

if [[ ! -d "$RESULTS_DIR" ]]; then
    echo "[stop_and_merge] ERROR: '$RESULTS_DIR' does not exist."
    exit 1
fi

# ---------------------------------------------------------------------------
# 1. Interrupt the running JVM
# ---------------------------------------------------------------------------
PID="$(pgrep -f 'monadvsim.*\.jar' 2>/dev/null | head -n1 || true)"
[[ -z "$PID" ]] && PID="$(pgrep -f 'com\.monadvsim\.app\.App' 2>/dev/null | head -n1 || true)"

if [[ -n "$PID" ]]; then
    echo "[stop_and_merge] Found simulation PID=$PID"
    echo "[stop_and_merge] Sending SIGINT (Ctrl+C equivalent)…"
    kill -INT "$PID" 2>/dev/null || true

    ELAPSED=0
    while kill -0 "$PID" 2>/dev/null; do
        if (( ELAPSED >= GRACE_SECONDS )); then
            echo
            echo "[stop_and_merge] Still alive after ${GRACE_SECONDS}s — sending SIGKILL"
            kill -KILL "$PID" 2>/dev/null || true
            sleep 2
            break
        fi
        printf '.'
        sleep 2
        ELAPSED=$(( ELAPSED + 2 ))
    done
    echo
    echo "[stop_and_merge] Process stopped."
else
    echo "[stop_and_merge] No running simulation found — merging existing files only."
fi

sleep 2

# ---------------------------------------------------------------------------
# 2. Collect snapshot files in tick order
# ---------------------------------------------------------------------------
cd "$RESULTS_DIR"

mapfile -t SNAPSHOTS < <(
    ls -1 snapshot_tick_*.csv 2>/dev/null \
    | sed -E 's/^snapshot_tick_([0-9]+)\.csv$/\1 &/' \
    | sort -n | cut -d' ' -f2
)

# If the App already wrote a merged file (graceful shutdown path), use it.
if (( ${#SNAPSHOTS[@]} == 0 )); then
    EXISTING_MERGE=$(ls -t merged_snapshots_*.csv 2>/dev/null | head -n1 || true)
    if [[ -n "$EXISTING_MERGE" ]]; then
        echo "[stop_and_merge] No snapshot files left; App already merged → $EXISTING_MERGE"
        OUTPUT="$EXISTING_MERGE"
    else
        echo "[stop_and_merge] No snapshot or merged files — nothing to do."
        exit 0
    fi
else
    TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
    OUTPUT="merged_snapshots_${TIMESTAMP}.csv"

    echo "[stop_and_merge] Found ${#SNAPSHOTS[@]} snapshot files."
    echo "[stop_and_merge] Tick range: $(echo "${SNAPSHOTS[0]}"  | grep -oE '[0-9]+') .. $(echo "${SNAPSHOTS[-1]}" | grep -oE '[0-9]+')"
    echo "[stop_and_merge] Writing $OUTPUT…"

    head -n 1 "${SNAPSHOTS[0]}" > "$OUTPUT"
    for f in "${SNAPSHOTS[@]}"; do
        tail -n +2 "$f" >> "$OUTPUT"
    done

    ROWS=$(( $(wc -l < "$OUTPUT") - 1 ))
    SIZE=$(du -h "$OUTPUT" | cut -f1)
    echo "[stop_and_merge]   rows: ${ROWS}"
    echo "[stop_and_merge]   size: ${SIZE}"

    if [[ "${KEEP_SNAPSHOTS:-0}" != "1" ]]; then
        for f in "${SNAPSHOTS[@]}"; do rm -f -- "$f"; done
        echo "[stop_and_merge] Deleted ${#SNAPSHOTS[@]} intermediate snapshot files."
        echo "[stop_and_merge]   (set KEEP_SNAPSHOTS=1 to keep them next time)"
    fi
fi

# ---------------------------------------------------------------------------
# 3. Derive statistics from the merged file
# ---------------------------------------------------------------------------
echo "[stop_and_merge] Computing partial statistics…"

FIRST_TICK=$(awk -F, 'NR>1 {gsub(/\r/,"",$1); if ($1+0<min || NR==2) min=$1+0} END {print min}' "$OUTPUT")
LAST_TICK=$(awk  -F, 'NR>1 {gsub(/\r/,"",$1); if ($1+0>max) max=$1+0} END {print max}' "$OUTPUT")

# Distinct ticks present
TICK_COUNT=$(awk -F, 'NR>1 {gsub(/\r/,"",$1); seen[$1+0]=1} END {print length(seen)}' "$OUTPUT")

# Agent counts per layer in the last snapshot
LAYER_COUNTS=$(awk -F, -v t="$LAST_TICK" '
    NR>1 {gsub(/\r/,"",$1); if ($1+0==t) layer[$3]++}
    END  {for (l in layer) printf "%-22s %,9d\n", l, layer[l]}' "$OUTPUT")

# Total agents on last tick
TOTAL_LAST=$(awk -F, -v t="$LAST_TICK" 'NR>1 {gsub(/\r/,"",$1); if ($1+0==t) n++} END {print n+0}' "$OUTPUT")

# Stage breakdown for the Mosquitoes (LivingAgent) layer on the last tick
STAGE_COUNTS=$(awk -F, -v t="$LAST_TICK" '
    NR>1 {gsub(/\r/,"",$1); if ($1+0==t && $4=="LivingAgent") stage[$9]++}
    END  {for (s in stage) printf "%-22s %,9d\n", s, stage[s]}' "$OUTPUT")

# InertAgent totals on last tick
read -r INERT_N INERT_EGGS INERT_LARVAE INERT_AVG_WATER <<<"$(
    awk -F, -v t="$LAST_TICK" '
        NR>1 {gsub(/\r/,"",$1); if ($1+0==t && $4=="InertAgent") {
            n++; eggs+=$12; larvae+=$13; water+=$14
        }}
        END {printf "%d %d %d %.2f", n+0, eggs+0, larvae+0, (n>0?water/n:0)}' "$OUTPUT")"

# Log file path
LOG=$(ls -t logs/simulation_*.log 2>/dev/null | head -n1 || true)

# Wall-clock span, from log first/last timestamps
FIRST_TS="" LAST_TS=""
if [[ -n "$LOG" && -f "$LOG" ]]; then
    FIRST_TS=$(head -n1 "$LOG" | grep -oE '^\[[0-9-]+ [0-9:]+' | tr -d '[')
    LAST_TS=$( tail -n1 "$LOG" | grep -oE '^\[[0-9-]+ [0-9:]+' | tr -d '[')
fi

# Extract last 3 DIAG lines for a mini-trajectory
DIAG_TAIL=""
if [[ -n "$LOG" ]]; then
    DIAG_TAIL=$(grep '\[DIAG\]' "$LOG" 2>/dev/null | tail -n3 || true)
fi

# Extract the max tick actually reached during the run (not just snapshots)
LOG_MAX_TICK=$(grep -oE '\[Monitor\] Tick: [0-9]+' "$LOG" 2>/dev/null \
    | grep -oE '[0-9]+' | sort -n | tail -n1 || true)
[[ -z "$LOG_MAX_TICK" ]] && LOG_MAX_TICK="$LAST_TICK"

# ---------------------------------------------------------------------------
# 4. Write the statistics file
# ---------------------------------------------------------------------------
STATS_TS="$(date +%Y%m%d_%H%M%S)"
STATS_FILE="Mosquito_Simulation_final_statistics_${STATS_TS}.txt"

{
    echo "================================================================================"
    echo "PARTIAL SIMULATION STATISTICS (interrupted run)"
    echo "================================================================================"
    echo
    echo "NOTE: this file was produced by stop_and_merge.sh, not by the JVM"
    echo "      shutdown hook. It reflects the state captured in the merged"
    echo "      snapshot file, not the live in-memory agent container."
    echo
    echo "SIMULATION METADATA"
    echo "----------------------------------------"
    printf "Project Name:           %s\n" "Mosquito Simulation"
    printf "Timestamp:              %s\n" "$(date '+%Y-%m-%d %H:%M:%S')"
    printf "Run started:            %s\n" "${FIRST_TS:-unknown}"
    printf "Run last log entry:     %s\n" "${LAST_TS:-unknown}"
    printf "Results directory:      %s\n" "$(pwd)"
    printf "Merged file:            %s\n" "$OUTPUT"
    printf "Snapshot files merged:  %d\n" "${#SNAPSHOTS[@]}"
    printf "Ticks captured:         %d (first=%s, last=%s)\n" "$TICK_COUNT" "$FIRST_TICK" "$LAST_TICK"
    printf "Log-reported last tick: %s\n" "$LOG_MAX_TICK"
    echo

    echo "AGENT STATISTICS (last snapshot: tick $LAST_TICK)"
    echo "----------------------------------------"
    if [[ -n "$LAYER_COUNTS" ]]; then
        echo "$LAYER_COUNTS"
    else
        echo "(none)"
    fi
    printf "%-22s %,9d\n" "TOTAL" "$TOTAL_LAST"
    echo

    echo "STAGE BREAKDOWN (LivingAgent, last snapshot)"
    echo "----------------------------------------"
    if [[ -n "$STAGE_COUNTS" ]]; then
        echo "$STAGE_COUNTS"
    else
        echo "(none)"
    fi
    echo

    echo "INERT AGENT TOTALS (last snapshot)"
    echo "----------------------------------------"
    printf "%-22s %,9d\n" "Tanks"           "$INERT_N"
    printf "%-22s %,9d\n" "Sum EggCount"    "$INERT_EGGS"
    printf "%-22s %,9d\n" "Sum LarvaCount"  "$INERT_LARVAE"
    printf "%-22s %s\n"   "Mean Water (%)"  "$INERT_AVG_WATER"
    echo

    echo "RECENT TRAJECTORY (last 3 census lines from log)"
    echo "----------------------------------------"
    if [[ -n "$DIAG_TAIL" ]]; then
        echo "$DIAG_TAIL"
    else
        echo "(no [DIAG] lines found in log)"
    fi
    echo

    echo "LOGGING"
    echo "----------------------------------------"
    printf "Log file:               %s\n" "${LOG:-<not found>}"
    if [[ -n "$LOG" && -f "$LOG" ]]; then
        printf "Log size:               %s\n" "$(du -h "$LOG" | cut -f1)"
    fi
    echo

    echo "================================================================================"
} > "$STATS_FILE"

echo "[stop_and_merge] Wrote $STATS_FILE"
echo
echo "================= Statistics preview ================="
cat "$STATS_FILE"
echo
echo "[stop_and_merge] Done."
