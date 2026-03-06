---
id: credential-management
title: Credential Management
sidebar_position: 3
---

# Credential Management

Credentials (passwords, private keys, tokens, secrets) are **never stored in plain text**. Every credential value is encrypted before it touches the database and decrypted only at connector creation time, in memory.

## Encryption Flow

```mermaid
sequenceDiagram
    actor Admin
    participant API as Onboarding API
    participant CS as CredentialService
    participant DB as integration_credential table
    participant REG as IntegrationRegistry

    Admin->>API: POST /integrations\n{ type: SFTP, password: "s3cr3t" }
    API->>CS: encrypt("s3cr3t", keyId=active_key)
    CS-->>API: encryptedValue + keyId
    API->>DB: INSERT { key_name: "password",\nencrypted_value: "AES256:...",\nencryption_key_id: "key-v3" }

    Note over DB: Plain text never written

    API->>REG: IntegrationCreatedEvent
    REG->>DB: SELECT encrypted credentials
    REG->>CS: decrypt(encryptedValue, keyId)
    CS-->>REG: "s3cr3t"
    REG->>REG: Build SftpConnector with live credentials
```

## Credential Rules

```mermaid
flowchart TD
    CR[Credential arrives in API request] --> ENC[Encrypt immediately\nAES-256-GCM + random IV]
    ENC --> DB[(Store encrypted_value\n+ encryption_key_id)]
    DB --> MASK[API response\nOMITS credential fields]

    DB --> DEC[Decrypt at connector creation\nonly in IntegrationRegistry]
    DEC --> MEM[Hold decrypted value\nin connector memory only]
    MEM --> USE[Use for connection\nNever log · Never serialize]

    style CR fill:#fee2e2,stroke:#ef4444
    style ENC fill:#fef9c3,stroke:#ca8a04
    style DB fill:#f3f4f6,stroke:#6b7280
    style MASK fill:#dcfce7,stroke:#16a34a
    style MEM fill:#dbeafe,stroke:#2563eb
    style USE fill:#dcfce7,stroke:#16a34a
```

## Credential Types by Integration

| Integration | Credential keys | Notes |
|-------------|----------------|-------|
| SFTP password | `password` | Encrypted string |
| SFTP private key | `privateKey`, `passphrase` | PEM encoded, passphrase optional |
| Kafka SASL | `saslUsername`, `saslPassword` | Used for SCRAM or PLAIN |
| REST Basic Auth | `basicPassword` | Base64 in Authorization header |
| REST Bearer / OAuth2 | `bearerToken` or `clientSecret` | Token or secret for OAuth2 flow |
| S3 | `accessKeyId`, `secretAccessKey` | Or use IAM role (no credentials needed) |
| AS2 | `privateKey`, `partnerCertificate` | For signing + encryption |

## Credential Rotation

```mermaid
flowchart LR
    A([Admin updates password\nvia PUT /integrations/:id/credentials]) --> B[New encrypted value written\nwith new rotation timestamp]
    B --> C[IntegrationUpdatedEvent published]
    C --> D[IntegrationRegistry tears down\nold connector]
    D --> E[New connector built\nwith fresh credentials]
    E --> F[Old connector drained\nand closed gracefully]

    style A fill:#fef9c3,stroke:#ca8a04
    style F fill:#dcfce7,stroke:#16a34a
```

:::tip Zero-downtime rotation
The registry keeps the old connector alive until the new one is healthy. In-flight writes complete on the old connector before it closes.
:::

## What is NEVER stored

- Plain text passwords anywhere in the DB
- Credentials in application logs (even DEBUG level)
- Credentials in API responses or error messages
- Credentials in Kafka event payloads
- Credentials in git (even `.env` files)
