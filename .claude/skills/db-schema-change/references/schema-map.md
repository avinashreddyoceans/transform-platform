# Schema map

Where the schema lives and which module owns each table's JPA entity. Read this
when applying a schema change so the entity edit (step 2 of the procedure) lands
in the right file.

## Source of truth

- **Schema file**: `platform-api/src/main/resources/db/schema.sql` — the full
  database schema. Edit this for every change.
- **No migration tool.** No Flyway, no Liquibase, no versioned migration files.
- **Hibernate DDL**: `spring.jpa.hibernate.ddl-auto: none` in `application.yml`.
  Hibernate never creates or alters tables — entities and `schema.sql` must be
  kept in agreement by hand.

## Tables and their entity modules

| Table | Entity module |
|-------|---------------|
| `file_specs` | `platform-api` |
| `profiles` | `platform-api` |
| `windows` | `platform-api` |
| `window_events` | `platform-api` |
| `window_action_executions` | `platform-api` |
| `workflow_step_executions` | `platform-api` |
| `file_log` | `platform-api` |
| `service_integrations` | `platform-integration` |
| `downloaded_files` | `platform-integration` |

The table list above can drift as the schema grows. To get the current,
authoritative list straight from the source file:

```bash
grep -iE 'CREATE TABLE' platform-api/src/main/resources/db/schema.sql
```

To find the exact entity file for a given table, grep for its `@Table` mapping:

```bash
grep -rn '@Table(name = "<table_name>"' platform-*/src/main/kotlin
```

## `schema.sql` editing style

- Tables and columns are `snake_case`.
- The file is plain PostgreSQL DDL with no version headers or comments-as-metadata.
- Keep new tables/columns consistent with the ordering and constraint style of
  the surrounding DDL (primary key first, foreign keys and indexes grouped).
- Postgres runs the file top to bottom once, so a table must be defined before
  anything that references it.
