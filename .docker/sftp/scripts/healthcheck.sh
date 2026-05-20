#!/bin/bash
# =============================================================================
# SFTP healthcheck — verifies the SSH daemon is accepting connections
# =============================================================================
# Docker runs this every 10s.  Returns 0 (healthy) or 1 (unhealthy).
# Uses nc (netcat) to check port 22 is open instead of a full SSH handshake,
# which avoids needing client credentials in the healthcheck.
# =============================================================================

SFTP_USER="${SFTP_USER:-sftpuser}"
SFTP_PASS="${SFTP_PASS:-sftppass}"
DATA_DIR="/home/${SFTP_USER}/upload"

# Check 1: SSH port is open
if ! nc -z localhost 22 2>/dev/null; then
    echo "UNHEALTHY: port 22 is not open"
    exit 1
fi

# Check 2: outbox and sent directories exist
for dir in inbox outbox sent; do
    if [ ! -d "${DATA_DIR}/${dir}" ]; then
        echo "UNHEALTHY: ${dir}/ directory missing"
        exit 1
    fi
done

# Check 3: watcher process is running
if ! pgrep -f "sftp-watcher" > /dev/null 2>&1; then
    echo "UNHEALTHY: watcher process not found"
    exit 1
fi

echo "HEALTHY: sshd up, folders ready, watcher running"
exit 0
