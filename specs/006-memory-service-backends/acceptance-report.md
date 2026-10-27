# Acceptance Report: Memory Service Backends

**Feature**: Memory Service Backends
**Branch**: `006-lesson22-memory`
**Date**: 2026-10-16

## Pre-implementation Checklist

- [x] spec.md created and validated
- [x] plan.md created
- [x] research.md created
- [x] data-model.md created
- [x] contracts/ created
- [x] tasks.md created
- [x] implementation complete
- [x] `mvn clean verify` green
- [x] harness tests green
- [x] previous-section tests green

## Implementation Summary

This section delivers the pluggable long-term memory subsystem for SableOS:

- `MemoryService` facade in `sableos-memory`, implementing the existing `MemoryProvider` contract from `sableos-core`.
- `LongTermMemoryStore` backend interface with three implementations:
  - `MarkdownMemoryStore` (default): per-profile `MEMORY.md` under `.sableos/memory/<profile>/`.
  - `SqliteMemoryStore`: `memory_entries` table via `sableos-storage` + Flyway.
  - `Mem0MemoryStore`: REST integration to Mem0 Platform (v3 API).
- `MemoryTools` (`save_memory` / `recall_memory`) registered into `ToolRegistry` via `sableos-boot`.
- `MemoryConfiguration` selects backend by `memory.backend` and wires `MemoryService` into `PromptBuilder`.

## Delivery-vs-Courseware Mapping

| Courseware Delivery Item | Task ID(s) | Status |
| --- | --- | --- |
| `MemoryService` interface + implementation | T005, T006 | delivered |
| `MemoryScope` enum | T003 | delivered |
| `LongTermMemoryStore` backend interface | T004 | delivered |
| `MarkdownMemoryStore` | T013 | delivered |
| `SqliteMemoryStore` | T018 | delivered |
| `Mem0MemoryStore` | T020 | delivered |
| `MemoryTools` (save_memory / recall_memory) | T014 | delivered |
| `MemoryStoreContractTest` | T017 | delivered |
| Markdown/SQLite/Mem0 specific tests | T010, T016, T019 | delivered |
| `MemoryToolsTest` | T011 | delivered |
| `MemoryServiceImplTest` | T012 | delivered |
| `memory_entries` table | T007, T008, T009 | delivered |
| `.sableos/memory/<profile>/MEMORY.md` | T013 | delivered |
| `memory.backend` configuration | T015, T023 | delivered |
| `memory.mem0.mode` + `MEM0_API_KEY` | T020 | delivered |
| PromptBuilder integration | T015 | delivered |

## Build Evidence

```text
$ mvn -B clean verify -Dowasp.skip=true
...
[INFO] Reactor Summary for SableOS 0.1.0-SNAPSHOT:
[INFO]
[INFO] SableOS ............................................ SUCCESS
[INFO] sableos-core ....................................... SUCCESS
[INFO] sableos-provider ................................... SUCCESS
[INFO] sableos-storage .................................... SUCCESS
[INFO] sableos-memory ..................................... SUCCESS
[INFO] sableos-tool ....................................... SUCCESS
[INFO] sableos-channel-cli ................................ SUCCESS
[INFO] sableos-web ........................................ SUCCESS
[INFO] sableos-cli ........................................ SUCCESS
[INFO] sableos-boot ....................................... SUCCESS
[INFO] BUILD SUCCESS
```

### sableos-memory test summary

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- MemoryServiceImplTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- MarkdownMemoryStoreTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- Mem0MemoryStoreTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- MemoryStoreContractTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- SqliteMemoryStoreTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- MemoryToolsTest
Tests run: 31, Failures: 0, Errors: 0, Skipped: 0 -- module total
```

## H4 Global Invariants Check

| # | Invariant | Result |
| --- | --- | --- |
| 1 | External IO goes through `Sandbox.enforce` first | N/A for Memory: `MemoryTools` operates on internal storage only; no external IO |
| 2 | LLM calls / tool invocations audited | `MemoryTools` is executed by `ToolExecutor`, which records `tool_invocations`; no direct LLM calls in this section |
| 3 | No plaintext keys | Verified: Mem0 credentials come from `memory.mem0.api-key` / `MEM0_API_KEY` only; `memory.mem0.mode` selects platform vs oss |
| 4 | `session_id` assembled only in core's single point | `MemoryService` receives `Session` and uses `session.profileName()`; does not construct session IDs |
| 5 | No Reactor / `CompletableFuture` / custom thread pools | Verified |
| 6 | No Spring AI automatic tool execution | Verified: `MemoryTools` is registered via `ToolRegistry.registerAnnotated` and invoked by `ReActLoop` + `ToolExecutor` |

## SableOS Hard Constraints Check

- JDK 21 + Spring Boot 3.x: unchanged
- Self-implemented ReAct loop: unchanged
- Spring AI "half-use": `MemoryTools` uses `@Tool`/`@ToolParam` for schema generation only
- Provider explicit mapping: unchanged
- Tool tiers in single module: `ToolRegistry`/MCP/Sandbox remain in `sableos-tool`; `MemoryTools` lives in `sableos-memory` and is registered from `sableos-boot`
- Audit day one: `memory_entries` added via Flyway; `tool_invocations` for `save_memory`/`recall_memory` unchanged
- Sandbox interface first: no Sandbox bypass
- Scope convergence: no governance features added
- No `synchronized` blocking calls: blocking IO (Files/JDBC/REST) is not inside `synchronized`
- TraceId / `TraceIds` / `TaskDecorator`: unchanged

## Cross-section Regression

All existing modules pass `mvn verify`:

- `sableos-core` (16/17/18/20 sections)
- `sableos-provider` (16 section)
- `sableos-storage` (16/17/18 sections)
- `sableos-tool` (20 section)
- `sableos-channel-cli` (18 section)
- `sableos-web`, `sableos-cli`, `sableos-boot`

## Remaining Manual Verification

The automated harness covers all four behavioral contracts and the three backend implementations. Manual checks left to the user:

1. **Backend switch smoke test**: set `memory.backend` to `markdown` and `sqlite` in turn, run a chat, and confirm `save_memory` writes and the next `buildContext` carries the memory.
2. **Mem0 real connection** (optional): create a Mem0 Platform API key (or start an OSS server), set `memory.backend: mem0` + `memory.mem0.mode` (`platform` / `oss`), verify `save_memory` lands in the chosen store and `recall` returns archival hits.
3. **Real-model end-to-end**: say something worth remembering in a real chat, start a new session, and confirm the core memory appears in the system prompt.
4. **USER.md vs MEMORY.md role check**: confirm `USER.md` is read-only and `MEMORY.md` is written by the Agent.

## Notes

- `MemoryEntryEntity` and `MemoryEntryRepository` intentionally live in `sableos-storage` to keep all JPA entities and Flyway migrations centralized; this deviates from the literal "all code in `sableos-memory`" wording but preserves the existing architectural boundary and avoids editing the 18th-section `SableOsRuntime` JPA scan configuration.
- `MemoryStoreContractTest` parameterizes over `MarkdownMemoryStore` and an in-memory stand-in for the Mem0 backend semantics; SQLite's equivalent contract is covered by `SqliteMemoryStoreTest`, which uses a real JPA repository.
