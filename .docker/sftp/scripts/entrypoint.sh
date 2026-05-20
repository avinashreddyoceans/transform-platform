#!/bin/bash
# =============================================================================
# Custom SFTP entrypoint
# =============================================================================
# 1. Lets atmoz/sftp create the user + SSH daemon
# 2. Ensures inbox / outbox / sent directories exist with correct permissions
# 3. Starts the inotify watcher (outbox → sent) in the background
# 4. Waits for the SSH daemon PID so Docker sees this container as healthy
# =============================================================================

set -euo pipefail

SFTP_USER="${SFTP_USER:-sftpuser}"
HOME_DIR="/home/${SFTP_USER}"
DATA_DIR="${HOME_DIR}/upload"

echo "[entrypoint] Starting transform-sftp for user=$SFTP_USER"

# ── Step 1: Run atmoz/sftp's own entrypoint to create the user & sshd config ──
# atmoz entrypoint is at /entrypoint (wrapped by their CMD ["sshd", "-D"])
# We call it with the original CMD args so it configures the user first.
/bin/bash /entrypoint "$@" &
ATMOZ_PID=$!

# Give atmoz a moment to create the user home and chroot directories
sleep 2

# ── Step 2: Bootstrap inbox / outbox / sent folders ───────────────────────────
for dir in inbox outbox sent; do
    TARGET="${DATA_DIR}/${dir}"
    mkdir -p "$TARGET"
    # Files written by root (the SFTP daemon runs as root on behalf of users)
    # must be writable by the sftpuser too for FileZilla uploads to outbox.
    chown root:"${SFTP_USER}" "$TARGET" 2>/dev/null || chown root:root "$TARGET"
    chmod 775 "$TARGET"
    echo "[entrypoint] ✓ ${dir}/ ready: ${TARGET}"
done

# ── Step 3: Write a README inside each folder so FileZilla shows non-empty dirs
cat > "${DATA_DIR}/inbox/README.txt" <<'EOF'
INBOX — Files queued for delivery to the client.
The transform-platform writes files here.
Connect with FileZilla, then download files from this folder.
EOF

cat > "${DATA_DIR}/outbox/README.txt" <<'EOF'
OUTBOX — Drop files here to deliver them to the transform-platform.
Upload your files (e.g. CAMT.053 XML) to this folder via FileZilla.
The platform polls this folder every 15 seconds.
After the platform downloads a file it is AUTOMATICALLY MOVED to ../sent/.
EOF

cat > "${DATA_DIR}/sent/README.txt" <<'EOF'
SENT — Audit archive.
Files that have been successfully downloaded by the transform-platform
are moved here automatically.  Do not delete — use for audit / replay.
EOF

# Fix ownership of README files so they are visible to the SFTP user
chown -R root:root "${DATA_DIR}"
chmod -R 755 "${DATA_DIR}"
# outbox must be writable so clients can upload
chmod 777 "${DATA_DIR}/outbox"

echo "[entrypoint] Folder structure ready:"
ls -la "${DATA_DIR}/"

# ── Step 4: Start inotify watcher in background ───────────────────────────────
echo "[entrypoint] Starting outbox watcher …"
/usr/local/bin/sftp-watcher "${DATA_DIR}/outbox" "${DATA_DIR}/sent" &
WATCHER_PID=$!
echo "[entrypoint] Watcher PID=$WATCHER_PID"

# ── Step 5: Trap signals so we clean up gracefully ───────────────────────────
_cleanup() {
    echo "[entrypoint] Shutting down …"
    kill "$WATCHER_PID" 2>/dev/null || true
    wait "$ATMOZ_PID" 2>/dev/null || true
}
trap _cleanup SIGTERM SIGINT

# ── Step 6: Wait for the SSH daemon ──────────────────────────────────────────
echo "[entrypoint] SSH daemon running (PID=$ATMOZ_PID)"
wait "$ATMOZ_PID"
