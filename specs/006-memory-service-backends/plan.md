# Implementation Plan: Memory Service Backends

**Branch**: `006-lesson22-memory` | **Date**: 2026-10-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-memory-service-backends/spec.md`

## Summary

Deliver a pluggable long-term memory subsystem for the SableOS runtime kernel. A unified `MemoryService` facade exposes build-context, remember, and recall operations to the ReAct loop and prompt assembly. A `LongTermMemoryStore` backend interface isolates persistence details, allowing three backends to coexist and be selected by configuration: a human-readable Markdown file backend, a structured SQLite backend, and a Mem0 Platform REST backend. The `save_memory` / `recall_memory` capabilities are exposed as built-in Agent tools, backend-agnostic and registered into the existing `ToolRegistry`.

## Technical Context

**Language/Version**: JDK 21

**Primary Dependencies**: Spring Boot 3.5.16, Spring AI 1.1.x (model annotations only), Spring Data JPA, Flyway, SQLite JDBC

**Storage**: SQLite (production-of-record for structured backend) + Markdown file (`.sableos/memory/MEMORY.md` for default backend)

**Testing**: JUnit 5, Mockito, `@TempDir` for file backend, H2/SQLite in-memory for repository tests, MockRestServiceServer for Mem0 backend

**Target Platform**: Single-node server, executable fat JAR

**Project Type**: Maven multi-module Java backend / Agent OS runtime kernel

**Performance Goals**: File/SQLite read-after-write latency under 100 ms for typical memory sizes (≤ few thousand entries)

**Constraints**: No in-process caching of long-term memory; core memory section must never be truncated; no Spring AI automatic tool execution; credentials via environment variables only

**Scale/Scope**: Single-node deployment; supports thousands of memory entries per agent/profile

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
| --- | --- | --- |
| I. JDK 21 + Spring Boot 3.x | Pass | No change to runtime or framework |
| II. Self-implemented ReAct loop | Pass | Memory is a callee of ReAct, not replacing it |
| III. Spring AI "half-use" | Pass | `MemoryTools` uses `@Tool`/`@ToolParam` only for schema generation; execution remains under `ReActLoop` + `ToolExecutor` |
| IV. Explicit provider mapping | Pass | No provider changes |
| V. Tool tiers in single module | Pass with note | `MemoryTools` is a built-in tool implemented in `sableos-memory`, but registered into `sableos-tool`'s `ToolRegistry` via `sableos-boot` assembly. The tool registry / MCP / Sandbox infrastructure itself remains in `sableos-tool` |
| VI. Audit day one | Pass | New `memory_entries` table via Flyway; no audit bypass |
| VII. Sandbox interface first | Pass | Memory operations are internal storage, no sandbox enforcement required |
| VIII. Core runtime kernel scope | Pass | Auto-extraction, vector DB, episodic memory, Memory Wiki, compression, KG backend remain out of scope |

### Complexity / module-location note

The specification says the implementation lands in `sableos-memory`. To keep the persistence layer unified, the `memory_entries` JPA entity and repository live in `sableos-storage` (same pattern as `SessionEntity`, `LlmCall`, `ToolInvocation`). `sableos-memory` depends on `sableos-storage` to use the repository. This avoids editing the 18th-section `SableOsRuntime` JPA scan configuration and keeps Flyway scripts in one place.

## Project Structure

### Documentation (this feature)

```text
specs/006-memory-service-backends/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
sableos-core/src/main/java/com/sableos/core/memory/
  MemoryProvider.java          # existing forward contract; MemoryService will implement it

sableos-memory/src/main/java/com/sableos/memory/
  MemoryService.java            # facade interface
  MemoryServiceImpl.java        # facade implementation, implements MemoryProvider
  MemoryScope.java              # CORE / ARCHIVAL enum
  LongTermMemoryStore.java      # backend interface
  store/
    MarkdownMemoryStore.java    # default file backend
    SqliteMemoryStore.java      # structured SQLite backend
    Mem0MemoryStore.java        # Mem0 Platform REST backend
  tool/
    MemoryTools.java            # @Tool bean: save_memory / recall_memory

sableos-memory/src/test/java/com/sableos/memory/
  MemoryStoreContractTest.java
  MarkdownMemoryStoreTest.java
  SqliteMemoryStoreTest.java
  Mem0MemoryStoreTest.java
  MemoryToolsTest.java
  MemoryServiceImplTest.java

sableos-storage/src/main/java/com/sableos/storage/memory/
  MemoryEntryEntity.java        # JPA mapping for memory_entries
  MemoryEntryRepository.java    # Spring Data JPA repository

sableos-storage/src/main/resources/db/migration/{sqlite,postgresql}/
  V4__memory_entries.sql        # Flyway script (manual DDL)

sableos-boot/src/main/java/com/sableos/boot/
  MemoryConfiguration.java      # backend selection & MemoryService bean + MemoryProvider injection + tool registration
```

**Structure Decision**: Memory contracts stay in `sableos-core`; implementation and tools in `sableos-memory`; JPA persistence in `sableos-storage` to keep Flyway/entities centralized; assembly wiring in `sableos-boot`.

## Complexity Tracking

> No constitution violations requiring justification.

| Decision | Why Needed | Alternative Rejected Because |
| --- | --- | --- |
| `memory_entries` entity in `sableos-storage` instead of `sableos-memory` | Keeps all JPA entities/Flyway migrations in the persistence module; avoids editing 18th-section `SableOsRuntime` scan packages | Putting it in `sableos-memory` would duplicate JPA dependencies and force a cross-module scan change |
| `MemoryTools` in `sableos-memory` but registered via `sableos-boot` | Tool semantics belong with Memory capability; registry infrastructure stays in `sableos-tool` | Putting `MemoryTools` in `sableos-tool` would couple Memory domain logic to the generic tool module |
