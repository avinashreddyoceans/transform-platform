# Transform Platform — SFTP Server Setup Guide

A local SFTP server with **inbox / outbox / sent** folder semantics, built on
`atmoz/sftp:alpine` with an inotify-based file watcher for automatic archiving.

---

## Quick Start

```bash
# From the project root — start SFTP alongside the core stack
docker compose \
  -f .docker/docker-compose.yml \
  -f .docker/docker-compose.sftp.yml \
  --profile core --profile sftp \
  up -d

# Or SFTP only (no database / Kafka / MinIO needed for quick FileZilla testing)
docker compose \
  -f .docker/docker-compose.sftp.yml \
  --profile sftp \
  up -d --build
```

Wait for the health check to pass (≈ 15 s):

```bash
docker ps --filter name=transform-sftp --format "{{.Status}}"
# → Up About a minute (healthy)
```

---

## Connection Credentials

| Setting    | Value     |
|------------|-----------|
| Protocol   | **SFTP – SSH File Transfer Protocol** *(not plain FTP)* |
| Host       | `localhost` |
| Port       | `2222` |
| Logon type | Normal |
| User       | `sftpuser` |
| Password   | `sftppass` |
| Remote dir | `/upload` |

> **Note:** Use **SFTP**, not FTP or FTPS. In FileZilla the option is in
> *File → Site Manager → Protocol → SFTP – SSH File Transfer Protocol*.

---

## FileZilla Step-by-Step

1. Open FileZilla → **File → Site Manager** (`Ctrl+S` / `Cmd+S`)
2. Click **New Site**, name it `transform-platform-sftp`
3. Set the fields:

   ```
   Protocol : SFTP – SSH File Transfer Protocol
   Host     : localhost
   Port     : 2222
   Logon    : Normal
   User     : sftpuser
   Password : sftppass
   ```

4. Click **Connect**
5. Accept the host key when prompted (first time only)
6. Navigate to `/upload` — you should see three folders:

   ```
   /upload/
   ├── inbox/    ← download files from here
   ├── outbox/   ← upload files here
   └── sent/     ← audit archive (do not modify)
   ```

---

## Folder Semantics

```
┌─────────────────────────────────────────────────────────────────┐
│  FileZilla / external client           transform-platform        │
│                                                                  │
│  Upload XML ──────────────────────► outbox/                      │
│                                        │                         │
│                                        │  platform polls every   │
│                                        │  15 seconds             │
│                                        ▼                         │
│                                     [Camel SFTP route]           │
│                                        │  downloads file         │
│                                        │                         │
│                             ┌──────────┴──────────┐             │
│                             │ inotify close_nowrite│             │
│                             │ fires on FD close    │             │
│                             └──────────┬──────────┘             │
│                                        │                         │
│  Download files ◄──────── inbox/    sent/  ← atomic mv          │
│                              ▲         ▲                         │
│  platform writes here ───────┘         └── audit archive         │
└─────────────────────────────────────────────────────────────────┘
```

| Folder   | Direction            | Who writes                       | Who reads                    |
|----------|----------------------|----------------------------------|------------------------------|
| `inbox/` | Platform → Client    | transform-platform               | FileZilla / external client  |
| `outbox/`| Client → Platform    | FileZilla / external client      | transform-platform (Camel)   |
| `sent/`  | Audit archive        | watcher.sh (automatic)           | nobody — read-only audit     |

### How `outbox → sent` works

The container runs an `inotifywait` watcher that monitors `outbox/` for
**`close_nowrite`** events. This event fires exactly once when a process that
opened a file for *reading only* closes the file descriptor — i.e., when the
SFTP download is fully complete.

When the event fires, the watcher atomically moves the file:

```
outbox/camt053_20260324.xml  →  sent/camt053_20260324.xml
                               (adds _YYYYMMDD_HHMMSS suffix if name collision)
```

You can watch this happen in real time:

```bash
docker logs -f transform-sftp
# [watcher] MOVED camt053_20260324.xml → sent/camt053_20260324.xml (at 2026-03-24T12:00:01)
```

---

## Manual File Operations

### Drop a file into outbox (simulate a client upload)

```bash
# From the project root
docker cp camt053_sample_file.xml \
  transform-sftp:/home/sftpuser/upload/outbox/camt053_$(date +%Y%m%d_%H%M%S).xml
```

### List current folder contents

```bash
docker exec transform-sftp ls -lR /home/sftpuser/upload/
```

### Watch watcher activity live

```bash
docker logs -f transform-sftp
```

### Reset (clear all files from all folders)

```bash
docker exec transform-sftp sh -c \
  'rm -f /home/sftpuser/upload/inbox/*.xml \
         /home/sftpuser/upload/outbox/*.xml \
         /home/sftpuser/upload/sent/*.xml'
```

---

## Health Check

The container runs a health check every 10 seconds via `/usr/local/bin/sftp-healthcheck`:

1. `nc -z localhost 22` — SSH port is open
2. `inbox/`, `outbox/`, `sent/` directories all exist
3. `sftp-watcher` process is running

```bash
# View health status
docker inspect transform-sftp --format '{{.State.Health.Status}}'
# → healthy

# View last 5 health check results
docker inspect transform-sftp \
  --format '{{range .State.Health.Log}}{{.Output}}{{end}}' \
  | tail -5
```

---

## Troubleshooting

### FileZilla: "Connection refused"
- Confirm the container is running: `docker ps | grep transform-sftp`
- Confirm port 2222 is bound: `docker port transform-sftp`
- Try `telnet localhost 2222` — should show an SSH banner

### FileZilla: "Host key mismatch"
Click **OK / Accept** — the key changes whenever the container is recreated.
To avoid prompts, add `localhost:2222` to FileZilla's trusted hosts
(*Edit → Settings → Connection → SFTP → Add key file*).

### File not moving from outbox to sent
Check the watcher is running:
```bash
docker exec transform-sftp pgrep -a sftp-watcher
```
If not running, restart the container:
```bash
docker compose -f .docker/docker-compose.sftp.yml --profile sftp restart sftp
```

### Container exits immediately
Check for port conflict on 2222:
```bash
lsof -i :2222
```
Change the host port in `docker-compose.sftp.yml` if needed:
```yaml
ports:
  - "2223:22"   # use 2223 instead
```

---

## Running the Full CAMT.053 E2E Demo

Once the SFTP server is healthy and the Spring Boot app is running:

```bash
# Fast mode — skips SFTP polling, submits file directly to the API
./run-camt053-e2e.sh --fast

# Full mode — drops file on SFTP, waits for Camel to poll and pick it up
./run-camt053-e2e.sh
```

See `run-camt053-e2e.sh` for teardown: `./run-camt053-e2e.sh --teardown`
