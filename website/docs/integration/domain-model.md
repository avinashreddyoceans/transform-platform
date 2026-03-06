---
id: domain-model
title: Domain Model
sidebar_position: 2
---

# Integration Domain Model

## Entity Relationships

```mermaid
erDiagram
    CLIENT {
        uuid id PK
        string code UK
        string name
        string status
        timestamp created_at
    }

    CLIENT_INTEGRATION {
        uuid id PK
        uuid client_id FK
        string name
        string type
        string direction
        string status
        jsonb config
        timestamp created_at
        timestamp updated_at
    }

    INTEGRATION_CREDENTIAL {
        uuid id PK
        uuid integration_id FK
        string key_name
        text encrypted_value
        string encryption_key_id
        timestamp rotated_at
    }

    INTEGRATION_EVENT_LOG {
        uuid id PK
        uuid integration_id FK
        string event_type
        string outcome
        string message
        timestamp occurred_at
    }

    CLIENT ||--o{ CLIENT_INTEGRATION : "has many"
    CLIENT_INTEGRATION ||--o{ INTEGRATION_CREDENTIAL : "has credentials"
    CLIENT_INTEGRATION ||--o{ INTEGRATION_EVENT_LOG : "has audit log"
```

## Integration Types

```mermaid
graph TD
    IT[IntegrationType]

    IT --> SFTP["SFTP\nhost · port · remotePath\nfilePattern · pollInterval"]
    IT --> FTP["FTP\nhost · port · remotePath\npassiveMode"]
    IT --> KAFKA["KAFKA\nbrokers · topic · groupId\nsaslMechanism · securityProtocol"]
    IT --> REST["REST / WEBHOOK\nbaseUrl · authType\nheaders · timeoutMs · retryPolicy"]
    IT --> S3["S3 / Cloud Storage\nbucket · region · prefix\npathStyle · storageClass"]
    IT --> AS2["AS2 (EDI)\npartnerId · url\nencryption · signing"]
    IT --> SMTP["SMTP / Email\nhost · port · from\nrecipients · subjectTemplate"]

    style SFTP fill:#dbeafe,stroke:#2563eb
    style KAFKA fill:#dcfce7,stroke:#16a34a
    style REST fill:#fef9c3,stroke:#ca8a04
    style S3 fill:#f3f4f6,stroke:#6b7280
    style FTP fill:#f3f4f6,stroke:#6b7280
    style AS2 fill:#fce7f3,stroke:#db2777
    style SMTP fill:#fce7f3,stroke:#db2777
```

## Integration Direction Matrix

```mermaid
quadrantChart
    title Integration directions by type
    x-axis "Inbound Only" --> "Outbound Only"
    y-axis "File-based" --> "Event / Message"
    SFTP Inbound: [0.1, 0.8]
    SFTP Outbound: [0.85, 0.8]
    FTP Inbound: [0.15, 0.75]
    S3 Inbound: [0.2, 0.7]
    S3 Outbound: [0.8, 0.7]
    Kafka Consumer: [0.1, 0.2]
    Kafka Producer: [0.85, 0.2]
    REST Webhook: [0.8, 0.35]
    REST Poll: [0.15, 0.35]
```

## Connector Interface Hierarchy

```mermaid
classDiagram
    class IntegrationConnector {
        <<interface>>
        +integrationId: UUID
        +clientId: UUID
        +type: IntegrationType
        +testConnection() ConnectionTestResult
        +healthCheck() HealthStatus
        +close()
    }

    class InboundConnector {
        <<interface>>
        +listFiles() List~RemoteFile~
        +downloadFile(file: RemoteFile) InputStream
        +acknowledgeFile(file: RemoteFile)
    }

    class OutboundConnector {
        <<interface>>
        +sendFile(name: String, content: InputStream, meta: FileMeta)
        +sendRecord(record: ParsedRecord, request: PipelineRequest)
    }

    class SftpConnector {
        -session: Session
        -config: SftpIntegrationConfig
        +testConnection()
        +listFiles()
        +downloadFile()
        +acknowledgeFile()
        +sendFile()
    }

    class KafkaConnector {
        -producer: KafkaProducer
        -consumer: KafkaConsumer
        +sendRecord()
        +testConnection()
    }

    class RestConnector {
        -httpClient: WebClient
        -authHandler: AuthHandler
        +sendRecord()
        +testConnection()
    }

    class S3Connector {
        -s3Client: S3Client
        +sendFile()
        +listFiles()
        +downloadFile()
        +acknowledgeFile()
    }

    IntegrationConnector <|-- InboundConnector
    IntegrationConnector <|-- OutboundConnector
    InboundConnector <|.. SftpConnector
    OutboundConnector <|.. SftpConnector
    OutboundConnector <|.. KafkaConnector
    OutboundConnector <|.. RestConnector
    InboundConnector <|.. S3Connector
    OutboundConnector <|.. S3Connector
```

## Type-Specific Configs

Each integration type has its own config class serialized as `JSONB` in the `config` column.

```mermaid
classDiagram
    class IntegrationConfig {
        <<sealed>>
    }

    class SftpIntegrationConfig {
        +host: String
        +port: Int = 22
        +username: String
        +remotePath: String
        +filePattern: String = "*"
        +pollIntervalSeconds: Int = 300
        +moveToPath: String?
        +deleteAfterDownload: Boolean = false
        +fileNamingPattern: String?
    }

    class KafkaIntegrationConfig {
        +brokers: List~String~
        +topic: String
        +groupId: String?
        +securityProtocol: String = "PLAINTEXT"
        +saslMechanism: String?
        +compressionType: String = "none"
    }

    class RestIntegrationConfig {
        +baseUrl: String
        +authType: AuthType
        +headers: Map~String, String~
        +timeoutMs: Int = 5000
        +retryAttempts: Int = 3
        +retryDelayMs: Int = 1000
    }

    class S3IntegrationConfig {
        +bucket: String
        +region: String
        +prefix: String = ""
        +pathStyle: Boolean = false
        +storageClass: String = "STANDARD"
        +moveToPrefix: String?
    }

    IntegrationConfig <|-- SftpIntegrationConfig
    IntegrationConfig <|-- KafkaIntegrationConfig
    IntegrationConfig <|-- RestIntegrationConfig
    IntegrationConfig <|-- S3IntegrationConfig
```
