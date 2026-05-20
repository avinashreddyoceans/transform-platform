#!/bin/bash
# =============================================================================
# outbox → sent watcher
# =============================================================================
# Watches the outbox directory using inotify.
# When the SFTP client (platform Camel route or FileZilla) finishes READING
# a file (close_nowrite event), the file is atomically moved to the sent/
# directory with a timestamp suffix to avoid name collisions.
#
# Usage:
#   sftp-watcher <outbox_dir> <sent_dir>
#
# Events used:
#   close_nowrite  — file was opened for reading only and then closed
#                    This fires exactly once per completed SFTP download.
#
# Notes:
#   • README.txt files are ignored (not moved).
#   • Directories are ignored.
#   • If a file with the same name already exists in sent/, a timestamp
#     suffix (_YYYYMMDD_HHMMSS) is appended to avoid overwriting.
#   • Errors are logged to stderr but do not crash the watcher.
# =============================================================================

set -euo pipefail

OUTBOX="${1:?Usage: sftp-watcher <outbox_dir> <sent_dir>}"
SENT="${2:?Usage: sftp-watcher <outbox_dir> <sent_dir>}"

echo "[watcher] Monitoring outbox: $OUTBOX"
echo "[watcher] Destination sent: $SENT"
echo "[watcher] Waiting for file download events …"
echo "[watcher] ──────────────────────────────────────"

# Use inotifywait in monitor mode (-m) so we never exit.
# --format '%e %f' prints  EVENT_NAME FILENAME  per line.
inotifywait \
    --monitor \
    --recursive \
    --event close_nowrite \
    --format '%T %e %f' \
    --timefmt '%Y-%m-%dT%H:%M:%S' \
    "$OUTBOX" 2>/dev/null \
| while IFS= read -r line; do

    TIMESTAMP=$(echo "$line" | awk '{print $1}')
    EVENT=$(echo "$line"     | awk '{print $2}')
    FILENAME=$(echo "$line"  | awk '{print $3}')

    # Skip READMEs, hidden files (.ssh, .swp etc.) and directories
    case "$FILENAME" in
        README.txt|.*)
            continue
            ;;
    esac

    SRC="$OUTBOX/$FILENAME"

    # Ensure it's a regular file that still exists (race-condition guard)
    [ -f "$SRC" ] || {
        echo "[watcher] SKIP  $FILENAME — no longer present (already moved?)"
        continue
    }

    # ── Build destination path ─────────────────────────────────────────────
    DEST="$SENT/$FILENAME"
    if [ -e "$DEST" ]; then
        # Avoid overwriting: add timestamp suffix before extension
        BASE="${FILENAME%.*}"
        EXT="${FILENAME##*.}"
        TS=$(date +%Y%m%d_%H%M%S)
        if [ "$BASE" = "$EXT" ]; then
            # No extension
            DEST="$SENT/${FILENAME}_${TS}"
        else
            DEST="$SENT/${BASE}_${TS}.${EXT}"
        fi
    fi

    # ── Atomic move ────────────────────────────────────────────────────────
    if mv "$SRC" "$DEST" 2>/dev/null; then
        echo "[watcher] MOVED $FILENAME  →  sent/$(basename "$DEST")  (at $TIMESTAMP)"
    else
        echo "[watcher] ERROR  Could not move '$FILENAME' to sent/ — skipped" >&2
    fi

done

echo "[watcher] inotifywait exited — watcher stopped"
