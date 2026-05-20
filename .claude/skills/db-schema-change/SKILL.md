---
name: db-schema-change
description: >-
  Apply a database schema change to the transform-platform. Use this whenever
  the user wants to add, alter, or drop a table, column, index, or constraint —
  or whenever a JPA entity change needs a matching database change. This project
  has NO Flyway/Liquibase migration tool: the whole schema is one file
  (schema.sql) that Postgres runs only once, so a schema change is a precise
  two-part procedure that is easy to get half-right. Trigger this skill on any
  mention of "migration", "new table", "add a column", "alter table", "schema
  change", "index", or editing schema.sql.
allowed-tools: Read, Edit, Bash, Grep, Glob
---

# Apply a database schema change

## Why this skill exists

This project deliberately has **no migration tool** (no Flyway, no Liquibase).
The entire database schema lives in a single file:

```
platform-api/src/main/resources/db/schema.sql
```

Two facts make schema changes a procedure rather than a one-liner:

1. **`schema.sql` runs only once.** `docker-compose.yml` mounts it into the
   Postgres container at `/docker-entrypoint-initdb.d/`. Postgres executes files
   there *only on the first start of an empty data volume*. Editing the file
   while a database already exists changes nothing in the running database.

2. **Hibernate never touches DDL.** `application.yml` sets
   `spring.jpa.hibernate.ddl-auto: none`. Hibernate will not create or alter
   tables to match the JPA entities — so an entity and its table can silently
   drift apart.

So a schema change is always two coordinated edits plus a database recreate.
The classic mistake is doing step 1 and forgetting step 3: the file changes,
the running database keeps the old schema, and the app fails with a column- or
table-not-found error that looks unrelated.

## Procedure

### 1. Edit `schema.sql`

Edit `platform-api/src/main/resources/db/schema.sql`. Match the existing style
of the file (column ordering, naming, constraint placement). Tables use
`snake_case`; the file is plain idempotent-ish DDL with no version headers.

### 2. Keep the JPA entity in sync

Because `ddl-auto` is `none`, the entity is *not* derived from the table — you
must change both. For any table you touched, find its entity and update it so
the `@Column` / `@Table` mappings match the new DDL.

To locate an entity, grep for its table name:

```bash
grep -rn '@Table(name = "<table_name>"' platform-*/src/main/kotlin
```

`references/schema-map.md` lists every table and which module owns its entity.

### 3. Recreate the database

The new `schema.sql` only takes effect on a fresh, empty Postgres volume, so the
local database must be recreated. **This destroys local data** — confirm with
the user before running it.

Run the bundled helper from the repo root:

```bash
.claude/skills/db-schema-change/scripts/recreate-db.sh
```

It prompts for confirmation, then runs the equivalent of:

```bash
docker compose -f .docker/docker-compose.yml --profile core down -v
docker compose -f .docker/docker-compose.yml --profile core up -d
```

### 4. Restart and verify

```bash
./restart-app.sh
```

Then confirm the app starts cleanly and the change is live — e.g. query the
table in pgAdmin (`http://localhost:5050`) or hit an endpoint that uses it.

## Gotchas

- **`down -v` is destructive.** It removes the `core` stack's volumes, so the
  Postgres database, Kafka topics, and MinIO objects are all wiped. Kafka topics
  and the MinIO bucket are recreated automatically; only Postgres data is a real
  loss — and it is local dev data. Never run this against a shared/remote DB.
- **Editing `schema.sql` alone does nothing.** If the volume already exists, the
  file is ignored on subsequent starts. The recreate in step 3 is mandatory.
- **Do not switch `ddl-auto` to `update` or `create`.** It is `none` on purpose
  so `schema.sql` stays the single source of truth. "Just letting Hibernate fix
  it" causes the entity and `schema.sql` to diverge — fix `schema.sql` instead.
- **Entity and DDL must agree on nullability, types, and names.** A mismatch
  surfaces at runtime, not at compile time.

## Reference

- `references/schema-map.md` — every table, the module that owns its entity, and
  notes on the `schema.sql` editing style.
