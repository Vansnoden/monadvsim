#!/usr/bin/env bash
# stop_and_merge.sh
#
# 1. Sends SIGINT to the running monadvsim process (same as Ctrl+C),
#    giving the App's shutdown hook a chance to flush + merge on its own.
# 2. If the process doesn't exit within the grace period, force-kills it.
# 3. Merges every snapshot_tick_*.csv in the results directory into one
#    file, using the same format ProjectPersistenceService writes.
#
# Usage:
#   ./stop_and_merge.sh [results_dir] [grace_seconds]
#
#   results_dir    default: results
#   grace_seconds  default: 120   (how long to wait for graceful shutdown)
#
# Flags:
#   KEEP_SNAPSHOTS=1 ./stop_and_merge.sh     # don't delete intermediates

set -uo pipefail   # NOT -e: we want to keep going past individual failures

RESULTS_DIR="${1:-results}"
GRACE_SECONDS="${2:-120}"

if [[ ! -d "$RESULTS_DIR" ]]; then
    echo "[stop_and_merge] ERROR: '$RESULTS_DIR' does not exist."
    exit 1
fi

# ---------------------------------------------------------------------------
# 1. Locate the running Java process
# ---------------------------------------------------------------------------
PID="$(pgrep -f 'monadvsim.*\.jar' 2>/dev/null | head -n1 || true)"
if [[ -z "$PID" ]]; then
    PID="$(pgrep -f 'com\.monadvsim\.app\.App' 2>/dev/null | head -n1 || true)"
fi

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

sleep 2   # let any half-written snapshot from the export thread flush

# ---------------------------------------------------------------------------
# 2. Collect snapshot files in tick order
# ---------------------------------------------------------------------------
cd "$RESULTS_DIR"

mapfile -t SNAPSHOTS < <(
    ls -1 snapshot_tick_*.csv 2>/dev/null \
    | sed -E 's/^snapshot_tick_([0-9]+)\.csv$/\1 &/' \
    | sort -n \
    | cut -d' ' -f2
)

if (( ${#SNAPSHOTS[@]} == 0 )); then
    echo "[stop_and_merge] No snapshot_tick_*.csv files in $RESULTS_DIR — nothing to merge."
    exit 0
fi

echo "[stop_and_merge] Found ${#SNAPSHOTS[@]} snapshot files."
echo "[stop_and_merge] Tick range: $(echo "${SNAPSHOTS[0]}" | grep -oE '[0-9]+') .. $(echo "${SNAPSHOTS[-1]}" | grep -oE '[0-9]+')"

# ---------------------------------------------------------------------------
# 3. Merge: header from first file, data from every file
# ---------------------------------------------------------------------------
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
OUTPUT="merged_snapshots_${TIMESTAMP}.csv"

echo "[stop_and_merge] Writing $OUTPUT…"

# Header (first line of first snapshot)
head -n 1 "${SNAPSHOTS[0]}" > "$OUTPUT"

# Concatenate data rows. `tail -n +2` skips the header of each file.
for f in "${SNAPSHOTS[@]}"; do
    tail -n +2 "$f" >> "$OUTPUT"
done

ROWS=$(( $(wc -l < "$OUTPUT") - 1 ))
SIZE=$(du -h "$OUTPUT" | cut -f1)
echo "[stop_and_merge] Merged ${#SNAPSHOTS[@]} files → $OUTPUT"
echo "[stop_and_merge]   rows: ${ROWS}"
echo "[stop_and_merge]   size: ${SIZE}"

# ---------------------------------------------------------------------------
# 4. Optionally delete intermediates (matches App's normal cleanup behaviour)
# ---------------------------------------------------------------------------
if [[ "${KEEP_SNAPSHOTS:-0}" != "1" ]]; then
    for f in "${SNAPSHOTS[@]}"; do
        rm -f -- "$f"
    done
    echo "[stop_and_merge] Deleted ${#SNAPSHOTS[@]} intermediate snapshot files."
    echo "[stop_and_merge]   (set KEEP_SNAPSHOTS=1 to keep them next time)"
fi

# ---------------------------------------------------------------------------
# 5. Quick per-stage summary so you can eyeball the last snapshot
# ---------------------------------------------------------------------------
LAST_TICK_LINE=$(tail -n 1 "$OUTPUT")
echo
echo "[stop_and_merge] Final row summary:"
echo "  Tick / AgentID / Layer / Stage / X / Y / Alive:"
echo "  ${LAST_TICK_LINE}" | cut -c1-120

echo
echo "[stop_and_merge] Done."
