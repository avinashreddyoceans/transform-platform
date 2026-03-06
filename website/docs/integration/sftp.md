---
id: sftp
title: SFTP Integration
sidebar_position: 5
---

# SFTP Integration

SFTP is the primary file transport protocol for enterprise partners. The platform supports both directions: **inbound** (we poll and download) and **outbound** (we push after transform).

## Library Choice

| Library | Verdict |
|---------|---------|
| **JSch** | Widely used, stable, but unmaintained (use fork `com.github.mwiede:jsch`) |
| **Apache MINA SSHD** | Actively maintained, full SSH2, better for connection pooling — **recommended** |
| **sshj** | Modern, clean API, good alternative |

**Use Apache MINA SSHD** (`org.apache.sshd:sshd-sftp`). It supports connection multiplexing, key exchange algorithms needed by modern servers, and a proper connection pool abstraction.

## Inbound (Download) Flow

```mermaid
flowchart TD
    SCHED(["Quartz Scheduler\npollIntervalSeconds"]) --> CONN["SftpConnector.connect\nreuse pooled session"]
    CONN --> LIST["listFiles - remotePath + filePattern"]
    LIST --> CHK{New files found?}
    CHK -->|No| SLEEP(["Wait for next poll"])
    CHK -->|Yes| LOCK["Acquire per-file lock\nprevent duplicate processing"]
    LOCK --> DOWN["downloadFile to InputStream"]
    DOWN --> PIPE["Feed into TransformationPipeline\nwith client FileSpec"]
    PIPE --> RESULT{Processing result}
    RESULT -->|Success| ACK["acknowledgeFile\nmove to /processed or delete"]
    RESULT -->|Failed| ERR["Move to /error\nlog + alert"]
    ACK --> NEXT{More files?}
    ERR --> NEXT
    NEXT -->|Yes| LOCK
    NEXT -->|No| SLEEP

    style SCHED fill:#dbeafe,stroke:#2563eb
    style ACK fill:#dcfce7,stroke:#16a34a
    style ERR fill:#fee2e2,stroke:#ef4444
    style SLEEP fill:#f3f4f6,stroke:#6b7280
```

## Outbound (Send) Flow

```mermaid
flowchart TD
    PIPE([TransformationPipeline completes]) --> RES[ProcessingResult\nhas outputFile]
    RES --> LOOKUP[IntegrationRegistry.get\nclientId + direction=OUTBOUND]
    LOOKUP --> FOUND{Outbound SFTP\nconfigured?}
    FOUND -->|No| SKIP[Log warning\nNo delivery]
    FOUND -->|Yes| CONN[SftpConnector.connect\nreuse session]
    CONN --> NAME["Apply fileNamingPattern\ne.g. output-date-correlationId.csv"]
    NAME --> SEND[sftp.put\nremotePath + filename]
    SEND --> OK{Transfer OK?}
    OK -->|Yes| LOG([Audit log: file delivered])
    OK -->|No| RETRY[Retry with backoff\nmax 3 attempts]
    RETRY --> FAIL{Still failing?}
    FAIL -->|Yes| ALERT[Alert + dead-letter]
    FAIL -->|No| LOG

    style PIPE fill:#dbeafe,stroke:#2563eb
    style LOG fill:#dcfce7,stroke:#16a34a
    style ALERT fill:#fee2e2,stroke:#ef4444
    style SKIP fill:#fef9c3,stroke:#ca8a04
```

## Connection Pool Design

```mermaid
flowchart LR
    subgraph pool["SftpConnectionPool (per integration)"]
        C1[Session 1\nidle]
        C2[Session 2\nin use]
        C3[Session 3\nidle]
    end

    REQ1[Download request] --> BORROW1[borrow session]
    BORROW1 --> C1
    C1 --> RETURN1[return session after use]

    REQ2[Upload request] --> BORROW2[borrow session]
    BORROW2 --> C3

    HB[Health check thread] -.->|ping every 30s| C2

    style pool fill:#dbeafe,stroke:#2563eb
    style HB fill:#f3f4f6,stroke:#6b7280
```

- Each `SftpConnector` owns its pool (max size configurable, default 3)
- Sessions are validated before borrow (send keep-alive / test channel)
- On validation failure: recreate session, re-authenticate
- Pool is torn down when integration is deleted or updated

## Authentication Options

```mermaid
flowchart LR
    AUTH{Auth method\nin SftpIntegrationConfig} -->|password| PW[username + password\nstored in CredentialStore]
    AUTH -->|privateKey| PK[PEM private key\n+ optional passphrase\nboth in CredentialStore]
    AUTH -->|keyboardInteractive| KI[Challenge-response\nnot recommended]

    style PW fill:#fef9c3,stroke:#ca8a04
    style PK fill:#dcfce7,stroke:#16a34a
    style KI fill:#fee2e2,stroke:#ef4444
```

Prefer **private key auth** for production — passwords are susceptible to brute force and rotation is harder.

## File Acknowledgement Strategy

| Strategy | Config | When to use |
|----------|--------|-------------|
| **Move to `/processed`** | `moveToPath: "/archive/processed"` | Partner needs to verify delivery |
| **Move to `/error`** | Automatic on pipeline failure | Quarantine bad files |
| **Delete after download** | `deleteAfterDownload: true` | Simplest, no archive needed |
| **Leave in place** | Default (no config) | When partner manages cleanup |

## SFTP Config Example

```json
{
  "name": "Bank ABC Inbound Transactions",
  "type": "SFTP",
  "direction": "INBOUND",
  "config": {
    "host": "sftp.bankabc.com",
    "port": 22,
    "username": "transform_svc",
    "remotePath": "/outbound/transactions/",
    "filePattern": "*.csv",
    "pollIntervalSeconds": 300,
    "moveToPath": "/outbound/transactions/processed",
    "authMethod": "PRIVATE_KEY"
  },
  "credentials": {
    "privateKey": "-----BEGIN RSA PRIVATE KEY-----\n...",
    "passphrase": "optional"
  }
}
```

:::warning
The `credentials` object in the request is **write-only**. It is encrypted immediately on receipt and never returned in GET responses.
:::
