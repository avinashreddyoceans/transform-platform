# =============================================================================
# transform-sftp — Custom SFTP server with inbox / outbox / sent semantics
# =============================================================================
#
# Based on atmoz/sftp (Alpine + OpenSSH).  Adds:
#   • inotify-tools  — file-system event watcher
#   • watcher.sh     — moves outbox → sent when platform downloads a file
#   • custom entrypoint that bootstraps all three folders with correct perms
#
# Folder semantics
# ─────────────────
#   inbox/   Platform → client (platform writes here; client downloads from here)
#   outbox/  Client  → platform (client/FileZilla uploads here; platform polls here)
#   sent/    Audit trail — platform-downloaded outbox files are moved here atomically
#
# Both the transform-platform Camel route and FileZilla connect to the same user.
# =============================================================================

FROM atmoz/sftp:alpine

# Add inotify-tools and bash (Alpine base ships with busybox sh only)
RUN apk add --no-cache inotify-tools bash coreutils

# ── Scripts ──────────────────────────────────────────────────────────────────
COPY scripts/watcher.sh       /usr/local/bin/sftp-watcher
COPY scripts/healthcheck.sh   /usr/local/bin/sftp-healthcheck
COPY scripts/entrypoint.sh    /entrypoint-custom.sh

RUN chmod +x /usr/local/bin/sftp-watcher \
             /usr/local/bin/sftp-healthcheck \
             /entrypoint-custom.sh

# ── Use our custom entrypoint (calls original atmoz entrypoint internally) ────
ENTRYPOINT ["/entrypoint-custom.sh"]
