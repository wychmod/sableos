# Tasks: Memory Service Backends

**Input**: Design documents from `/specs/006-memory-service-backends/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Included — the feature specification explicitly defines an acceptance harness.

**Organization**: Tasks grouped by user story; foundational work is completed first, then each backend is added and validated.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: User story label (US1, US2, US3)
- Exact file paths are included in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add module dependencies and shared constants.

- [x] T001 Add `sableos-memory` dependencies: `sableos-storage`, `spring-boot-starter-data-jpa`, `spring-web`, `spring-ai-model` in `sableos-memory/pom.xml`
- [x] T002 Add `sableos-memory` dependency to `sableos-boot/pom.xml` (already present; verify)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Define the facade, backend interface, scope enum, and persistence entity before any backend is implemented.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T003 [P] Create `MemoryScope` enum (`CORE`, `ARCHIVAL`) in `sableos-memory/src/main/java/com/sableos/memory/MemoryScope.java`
- [x] T004 [P] Create `LongTermMemoryStore` interface in `sableos-memory/src/main/java/com/sableos/memory/LongTermMemoryStore.java`
- [x] T005 Create `MemoryService` interface in `sableos-memory/src/main/java/com/sableos/memory/MemoryService.java`
- [x] T006 Create `MemoryServiceImpl` in `sableos-memory/src/main/java/com/sableos/memory/MemoryServiceImpl.java` (implements `MemoryService` and `com.sableos.core.memory.MemoryProvider`)
- [x] T007 Create `MemoryEntryEntity` in `sableos-storage/src/main/java/com/sableos/storage/memory/MemoryEntryEntity.java` (columns: `id` auto-increment, `profile_name` TEXT NOT NULL, `scope` VARCHAR(16) NOT NULL, `content` TEXT NOT NULL, `created_at` INTEGER/TIMESTAMP NOT NULL)
- [x] T008 Create `MemoryEntryRepository` in `sableos-storage/src/main/java/com/sableos/storage/memory/MemoryEntryRepository.java`
- [x] T009 Create Flyway migration `V4__memory_entries.sql` in both `sableos-storage/src/main/resources/db/migration/sqlite/` and `sableos-storage/src/main/resources/db/migration/postgresql/`

**Checkpoint**: Foundation contracts and persistence ready.

---

## Phase 3: User Story 1 - Agent Remembers User Preferences Across Sessions (Priority: P1) 🎯 MVP

**Goal**: Provide a working default Markdown backend and expose save/recall tools so an Agent can remember a user preference and recall it in a new session.

**Independent Test**: `MemoryServiceImplTest` + `MemoryToolsTest` + `MarkdownMemoryStoreTest` pass; manual chat test shows remembered preference.

### Tests for User Story 1 (write first, ensure they fail before implementation)

- [x] T010 [P] [US1] Create `MarkdownMemoryStoreTest` in `sableos-memory/src/test/java/com/sableos/memory/store/MarkdownMemoryStoreTest.java`
- [x] T011 [P] [US1] Create `MemoryToolsTest` in `sableos-memory/src/test/java/com/sableos/memory/tool/MemoryToolsTest.java`
- [x] T012 [US1] Create `MemoryServiceImplTest` in `sableos-memory/src/test/java/com/sableos/memory/MemoryServiceImplTest.java`

### Implementation for User Story 1

- [x] T013 [US1] Implement `MarkdownMemoryStore` in `sableos-memory/src/main/java/com/sableos/memory/store/MarkdownMemoryStore.java` (file path `.sableos/memory/<profile>/MEMORY.md`, core/archive headers, no caching, truncation only on archive)
- [x] T014 [US1] Implement `MemoryTools` in `sableos-memory/src/main/java/com/sableos/memory/tool/MemoryTools.java` (`@Tool` methods `save_memory` and `recall_memory`, default scope `archival`, invalid scope returns clear error)
- [x] T015 [US1] Create `MemoryConfiguration` in `sableos-boot/src/main/java/com/sableos/boot/MemoryConfiguration.java` (select backend by `memory.backend`, wire `MemoryServiceImpl` into `PromptBuilder` via `MemoryProvider`, provide `MemoryTools` bean for `ToolConfiguration`)

**Checkpoint**: US1 functional — Markdown backend, tools, and prompt integration work.

---

## Phase 4: User Story 2 - Zero-Code Backend Swap as Memory Volume Grows (Priority: P2)

**Goal**: Add the SQLite backend and prove the backend contract is interchangeable via a parameterized contract test.

**Independent Test**: `SqliteMemoryStoreTest` + `MemoryStoreContractTest` pass.

### Tests for User Story 2

- [x] T016 [P] [US2] Create `SqliteMemoryStoreTest` in `sableos-memory/src/test/java/com/sableos/memory/store/SqliteMemoryStoreTest.java`
- [x] T017 [US2] Create `MemoryStoreContractTest` in `sableos-memory/src/test/java/com/sableos/memory/store/MemoryStoreContractTest.java` (parameterized over Markdown/Mem0-in-memory, SQLite covered by `SqliteMemoryStoreTest`; covers four behavioral contracts)

### Implementation for User Story 2

- [x] T018 [US2] Implement `SqliteMemoryStore` in `sableos-memory/src/main/java/com/sableos/memory/store/SqliteMemoryStore.java` (insert via `MemoryEntryRepository`, core full select, archive `LIMIT` select, `LIKE` recall)

**Checkpoint**: US2 functional — switching `memory.backend` to `sqlite` works with zero calling-code changes.

---

## Phase 5: User Story 3 - Optional Semantic Recall via Self-Hosted External Memory (Priority: P3)

**Goal**: Add the Mem0 REST backend for organizations that need semantic retrieval.

**Independent Test**: `Mem0MemoryStoreTest` passes with mocked `RestClient`.

### Tests for User Story 3

- [x] T019 [US3] Create `Mem0MemoryStoreTest` in `sableos-memory/src/test/java/com/sableos/memory/store/Mem0MemoryStoreTest.java` (mock `RestClient`, assert request body carries scope, assert recall forwards query)

### Implementation for User Story 3

- [x] T020 [US3] Implement `Mem0MemoryStore` in `sableos-memory/src/main/java/com/sableos/memory/store/Mem0MemoryStore.java` (REST calls to `/v1/memories/` add/get/search, credentials via `${MEM0_BASE_URL}` / `${MEM0_API_KEY}` environment variables)

**Checkpoint**: US3 functional — Mem0 backend can be selected by configuration.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Full build verification, documentation drift check, and branch hygiene.

- [x] T021 Run `mvn spotless:apply` and `mvn -q -B clean verify -Dowasp.skip=true`; fix any SpotBugs/P3C/Checkstyle failures
- [x] T022 Run all module tests and confirm pre-existing 16-20 section tests still pass
- [x] T023 Verify `memory.backend` default remains `markdown` in `sableos-boot/src/main/resources/application.yaml`
- [x] T024 Update `specs/006-memory-service-backends/acceptance-report.md` with build evidence and remaining manual verification steps

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies.
- **Foundational (Phase 2)**: Depends on Setup. Blocks all user stories.
- **User Story 1 (Phase 3)**: Depends on Foundational. MVP.
- **User Story 2 (Phase 4)**: Depends on Foundational and US1 (Markdown store exists as contract baseline).
- **User Story 3 (Phase 5)**: Depends on Foundational.
- **Polish (Phase 6)**: Depends on all stories.

### User Story Dependencies

- **US1 (P1)**: No other story dependency; delivers first working backend and tools.
- **US2 (P2)**: Builds on the contract established in US1; adds SQLite and contract tests.
- **US3 (P3)**: Independent of US1/US2 implementation details; only needs foundational interfaces.

### Parallel Opportunities

- T003, T004, T007, T008 can run in parallel.
- T010, T011, T012 (US1 tests) can run in parallel.
- T016, T017 (US2 tests) can run in parallel after T018 interface is stable.
- US1, US2, US3 implementations can proceed in parallel once foundational interfaces are in place.

---

## Implementation Strategy

### MVP First (US1 Only)

1. Complete Phase 1 + Phase 2.
2. Complete Phase 3 (US1) — Markdown backend + tools + boot wiring.
3. Stop and validate with `mvn test -pl sableos-memory`.

### Incremental Delivery

1. Add US1 → validate.
2. Add US2 → validate SQLite + contract tests.
3. Add US3 → validate Mem0 backend.
4. Run full `mvn clean verify`.

---

## Notes

- All Java files must pass Spotless + P3C + Checkstyle + SpotBugs.
- Test method names must be English; use `@DisplayName` to preserve Chinese test intent from the courseware.
- No Spring AI automatic tool execution; `ToolRegistry.registerAnnotated` is used explicitly.
