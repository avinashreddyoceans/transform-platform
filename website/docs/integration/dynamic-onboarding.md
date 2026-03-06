---
id: dynamic-onboarding
title: Dynamic Onboarding
sidebar_position: 4
---

# Dynamic Onboarding

When a new client is onboarded, their integrations are configured via the API at runtime — no restarts, no code changes, no redeploys.

## Onboarding Flow — New Client

```mermaid
flowchart TD
    A([Admin opens Onboarding UI]) --> B[Create client\nPOST /clients]
    B --> C[Add integrations one by one\nPOST /clients/:id/integrations]

    C --> D{Integration type?}

    D -->|SFTP Inbound| SI["Configure:\nhost · port · remotePath\nfilePattern · pollInterval\ncredentials"]
    D -->|SFTP Outbound| SO["Configure:\nhost · port · remotePath\nfileNamingPattern\ncredentials"]
    D -->|Kafka| KA["Configure:\nbrokers · topic\ngroupId · security\ncredentials"]
    D -->|REST Webhook| RE["Configure:\nbaseUrl · authType\nheaders · retry policy\ncredentials"]
    D -->|S3| S3["Configure:\nbucket · region · prefix\ncredentials or IAM role"]

    SI --> TEST[Test connection\nPOST /integrations/:id/test]
    SO --> TEST
    KA --> TEST
    RE --> TEST
    S3 --> TEST

    TEST --> CHK{Connection OK?}
    CHK -->|Yes| ACTIVATE[Set status = ACTIVE\nConnector registered in registry]
    CHK -->|No| ERR[Return error details\nIntegration stays INACTIVE]

    ACTIVATE --> LIVE([Integration is live\nNo restart needed])

    style A fill:#dbeafe,stroke:#2563eb
    style LIVE fill:#dcfce7,stroke:#16a34a
    style ERR fill:#fee2e2,stroke:#ef4444
```

## IntegrationRegistry Lifecycle

```mermaid
stateDiagram-v2
    [*] --> Loading: App startup

    Loading --> Active: Loaded from DB\nconnection OK
    Loading --> Inactive: Loaded from DB\nconnection failed

    Active --> Reconnecting: Health check failed
    Reconnecting --> Active: Reconnect successful
    Reconnecting --> Error: Max retries exceeded

    Active --> Updating: IntegrationUpdatedEvent received
    Updating --> Active: New connector built OK
    Updating --> Error: New config invalid

    Active --> Closed: IntegrationDeletedEvent
    Error --> [*]: Manual intervention required
    Closed --> [*]
```

## API Endpoints

```mermaid
graph LR
    subgraph clients["Client Management"]
        C1["POST /api/v1/clients\nCreate client"]
        C2["GET /api/v1/clients\nList clients"]
        C3["PUT /api/v1/clients/:id\nUpdate client"]
    end

    subgraph integrations["Integration Management"]
        I1["POST /api/v1/clients/:id/integrations\nAdd integration + credentials"]
        I2["GET /api/v1/clients/:id/integrations\nList integrations"]
        I3["PUT /api/v1/integrations/:id\nUpdate config (hot-reload)"]
        I4["PUT /api/v1/integrations/:id/credentials\nRotate credentials (hot-reload)"]
        I5["DELETE /api/v1/integrations/:id\nRemove + close connector"]
        I6["POST /api/v1/integrations/:id/test\nTest live connection"]
        I7["GET /api/v1/integrations/:id/health\nHealth + last activity"]
    end

    C1 --> I1
```

## Hot-Reload — Update Without Restart

```mermaid
sequenceDiagram
    actor Admin
    participant API as Onboarding API
    participant SVC as IntegrationService
    participant DB as Database
    participant BUS as ApplicationEventPublisher
    participant REG as IntegrationRegistry

    Admin->>API: PUT /integrations/:id\n(new host or credentials)
    API->>SVC: updateIntegration(id, request)
    SVC->>DB: UPDATE client_integration + credentials
    SVC->>BUS: publish IntegrationUpdatedEvent(id)
    BUS->>REG: onIntegrationUpdated(event)
    REG->>DB: reload config + decrypt credentials
    REG->>REG: build new connector, test connection
    REG->>REG: swap old connector → new
    REG->>REG: drain old connector, close()
    REG-->>API: done
    API-->>Admin: 200 OK
```

## Connector Startup on App Boot

```mermaid
flowchart TD
    START([Application startup]) --> LOAD[IntegrationRegistry.init]
    LOAD --> DB[(Load all ACTIVE integrations\nfrom DB)]
    DB --> LOOP{For each integration}
    LOOP --> CRED[Decrypt credentials\nfrom integration_credential]
    CRED --> BUILD[Build connector\nvia IntegrationFactory]
    BUILD --> TEST{Test connection?}
    TEST -->|OK| REG[Register in connector map\nstatus = ACTIVE]
    TEST -->|FAIL| WARN[Log warning\nstatus = ERROR\nRetry scheduled]
    REG --> LOOP
    WARN --> LOOP
    LOOP --> SCHED[Schedule SFTP inbound polling\nfor all INBOUND connectors]
    SCHED --> DONE([Platform ready])

    style START fill:#dbeafe,stroke:#2563eb
    style DONE fill:#dcfce7,stroke:#16a34a
    style WARN fill:#fef9c3,stroke:#ca8a04
```
