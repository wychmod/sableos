# Data Model: Memory Service Backends

**Feature**: Memory Service Backends
**Date**: 2026-10-16

## Overview

Long-term memory entries are stored per backend, but the SQLite backend uses a single table `memory_entries`. The table is the persistence-of-record for the structured backend and follows the same Flyway-managed, `ddl-auto: none` discipline as `sessions`, `llm_calls`, and `tool_invocations`.

## Entity: Memory Entry

### Table `memory_entries`

| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `id` | INTEGER / BIGSERIAL | No | Primary key, auto-increment |
| `scope` | VARCHAR(16) | No | `CORE` or `ARCHIVAL` |
| `content` | TEXT | No | The memory text written by the Agent |
| `created_at` | TIMESTAMP / INTEGER | No | Entry creation timestamp |

### Indexes

- `idx_memory_scope` on `scope` — speeds core-only and archival-only queries.

### SQLite migration

```sql
CREATE TABLE IF NOT EXISTS memory_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scope VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_scope ON memory_entries (scope);
```

### PostgreSQL migration

```sql
CREATE TABLE IF NOT EXISTS memory_entries (
    id BIGSERIAL PRIMARY KEY,
    scope VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_scope ON memory_entries (scope);
```

## Domain Concepts

- **MemoryScope**: `CORE` (always present in full) or `ARCHIVAL` (searchable, truncatable).
- **Core memory**: Written when the Agent calls `save_memory` with `scope=core`. Loaded completely on every context build.
- **Archival memory**: Written when the Agent calls `save_memory` with `scope=archival` (default). Included in context only up to a configured limit (oldest dropped first), and searched by `recall_memory`.

## Notes

- The table is only used by `SqliteMemoryStore`. `MarkdownMemoryStore` uses `.sableos/memory/MEMORY.md`; `Mem0MemoryStore` delegates to the external service.
- `created_at` uses the same storage convention as other tables: SQLite stores Unix epoch milliseconds (`INTEGER`), PostgreSQL stores `TIMESTAMP`.
