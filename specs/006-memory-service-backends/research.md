# Research: Memory Service Backends

**Feature**: Memory Service Backends
**Date**: 2026-10-16

## Decisions

### D-01: Two-layer abstraction — facade `MemoryService` + backend `LongTermMemoryStore`

**Decision**: Expose a single `MemoryService` facade to the rest of the runtime, and push all persistence details behind a `LongTermMemoryStore` interface. `MemoryService` implements the existing `MemoryProvider` contract so `PromptBuilder` can consume it without change.

**Rationale**: This is the implementation-level payoff of the "interface wall" from the §21 review. `ReActLoop`, `PromptBuilder`, and `MemoryTools` depend only on the facade; swapping backends changes only the injected store implementation and a single configuration property.

**Alternatives considered**:
- Direct file/DB access scattered through callers — rejected because it would hard-code persistence choices into ReAct and tools.
- Spring AI's built-in memory abstractions — rejected by Constitution III; we keep full control over loop and tool execution.

### D-02: Three backend implementations: Markdown (default), SQLite, Mem0

**Decision**: Provide three implementations selected by `memory.backend`:

- `MarkdownMemoryStore` — human-readable `.sableos/memory/MEMORY.md`, zero external dependencies, default.
- `SqliteMemoryStore` — row-per-entry in `memory_entries`, structured query and `LIMIT` truncation, still zero external dependencies.
- `Mem0MemoryStore` — REST client to a self-hosted Mem0 server, for semantic retrieval / conflict resolution.

**Rationale**: Matches the staged evolution in §21: file-first, then structured DB, then external intelligence. The default keeps the runtime self-contained; Mem0 is opt-in and self-hosted to respect data-sovereignty requirements.

**Alternatives considered**:
- Only Markdown — rejected because it would not prove the pluggability contract under volume.
- Only SQLite — rejected because it would leave no path for semantic recall without adding vector infrastructure to core.
- Built-in vector DB (e.g., Chroma, LanceDB) — rejected by Constitution VI and §5.5 scope; vectors are extension-stage.

### D-03: Persistent entities live in `sableos-storage`

**Decision**: The `memory_entries` JPA entity and repository live in `sableos-storage`, alongside `SessionEntity`, `LlmCall`, and `ToolInvocation`. `sableos-memory` depends on `sableos-storage` to use the repository.

**Rationale**: Keeps all Flyway scripts and JPA mappings in one persistence module; avoids editing the 18th-section `SableOsRuntime` `@EntityScan`/`@EnableJpaRepositories` packages.

**Alternatives considered**:
- Entities in `sableos-memory` — rejected because it would duplicate JPA/SQLite dependencies and force a cross-module scan change.

### D-04: `MemoryTools` implemented in `sableos-memory`, registered via `sableos-boot`

**Decision**: `MemoryTools` (the `@Tool` bean for `save_memory` / `recall_memory`) is authored in `sableos-memory` but registered into the `sableos-tool` `ToolRegistry` by a `sableos-boot` configuration class.

**Rationale**: Tool semantics belong with the Memory capability, while the registry/MCP/Sandbox infrastructure remains centralized in `sableos-tool` per Constitution V.

### D-05: Mem0 REST API shape (self-hosted)

**Decision**: Use Spring `RestClient` to call a self-hosted Mem0 server's v1 REST endpoints. The implementation treats the exact JSON shape as an integration detail and uses a small internal DTO layer so tests can mock `RestClient` without relying on a live server.

**Rationale**: Mem0's API is versioned; pinning to exact field names in core code would create fragility. Mock-based tests assert request structure and response handling.

**Known endpoints** (used as reference; implementation validates against tests):
- `POST /v1/memories/` — add a memory; body carries `messages`, `user_id`, and metadata including `scope`.
- `GET /v1/memories/?user_id={user_id}` — list memories for a user.
- `POST /v1/memories/search/` — semantic search; body carries `query` and `user_id`.

### D-06: Four behavioral contracts enforced by parameterized contract tests

**Decision**: Encode the following as a shared contract test suite run against every backend:

1. No caching — every `load()`/`recallByKeyword()` reads fresh data.
2. Core memory is never truncated; truncation applies only to archival memory.
3. Scope is explicit on every write (`core` or `archival`).
4. `recall` only searches archival memory (core is already injected in full).

**Rationale**: These are the invariants that make the backends interchangeable. A parameterized test ensures any future backend or optimization cannot silently break them.

## Open Questions Resolved

- **Where does the JPA entity go?** → `sableos-storage` (D-03).
- **How does `MemoryTools` reach `ToolRegistry` without coupling modules?** → `sableos-boot` registers the bean (D-04).
- **How is Mem0 tested without a live server?** → Mock `RestClient` with `MockRestServiceServer` (D-05).
