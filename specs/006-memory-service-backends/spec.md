# Feature Specification: Memory Service Backends

**Feature Branch**: `006-lesson22-memory`

**Created**: 2026-10-16

**Status**: Draft

**Input**: User description: "第22节需求：sableos-memory——长期记忆可插拔门面与三档后端"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Agent Remembers User Preferences Across Sessions (Priority: P1)

During a conversation, a user tells the Agent something worth remembering, such as their preferred programming language or name. The Agent explicitly stores this as a long-term memory. In a later conversation, even after the current session has ended, the Agent can recall the stored preference and personalize its response without the user repeating it.

**Why this priority**: This is the core value proposition of long-term memory in an Agent OS. Without it, every conversation starts from zero, making the Agent feel stateless and impersonal.

**Independent Test**: Can be fully tested by storing a fact, simulating a new session, and verifying that the fact appears in the context assembled for the next interaction.

**Acceptance Scenarios**:

1. **Given** the Agent has no prior memory of a user, **When** the user says "I prefer Java" and the Agent stores it as core memory, **Then** a new session with the same user includes "I prefer Java" in the Agent's working context.
2. **Given** a stored core memory exists, **When** the user later asks "What do you know about me?", **Then** the Agent's working context contains the previously stored fact.

---

### User Story 2 - Zero-Code Backend Swap as Memory Volume Grows (Priority: P2)

As the number of stored memories grows from a handful to hundreds or thousands, operators need to switch the underlying long-term memory backend without rewriting Agent logic, tool definitions, or prompt assembly code. The same user-facing behavior is preserved; only the persistence mechanism changes.

**Why this priority**: Demonstrates that the long-term memory layer is a pluggable boundary rather than a hard-coded implementation, which is the architectural guarantee established in the previous review session.

**Independent Test**: Can be fully tested by running the same contract tests against each supported backend and confirming identical behavior for read-after-write, truncation boundaries, scope routing, and keyword recall.

**Acceptance Scenarios**:

1. **Given** the system is configured to use the default file-based backend, **When** a large number of archival memories are stored, **Then** only the oldest archival memories are dropped while every core memory remains intact.
2. **Given** the same contract tests, **When** executed against the structured database backend, **Then** all assertions pass without changing any calling code.

---

### User Story 3 - Optional Semantic Recall via Self-Hosted External Memory (Priority: P3)

When the organization needs semantic retrieval, conflict resolution, or automatic summarization beyond simple keyword search, it can point the system to a self-hosted external memory service. The Agent-facing save and recall tools continue to work unchanged, and data remains within the organization's network.

**Why this priority**: Keeps the core codebase free of heavy retrieval infrastructure while providing a credible upgrade path for advanced use cases.

**Independent Test**: Can be validated by configuring the external backend and verifying that save and recall requests are translated to the external service's protocol; exact semantic behavior is validated against the external service's own test environment.

**Acceptance Scenarios**:

1. **Given** the system is configured to use the external backend, **When** the Agent saves a memory, **Then** the request is forwarded to the configured self-hosted service with the correct scope metadata.
2. **Given** the system is configured to use the external backend, **When** the Agent recalls a keyword, **Then** the query is forwarded to the external service and the result is returned in the same format as other backends.

---

### Edge Cases

- What happens when the Agent calls recall with a keyword that matches nothing? The system returns a clear "not found" message instead of throwing an exception.
- What happens when the Agent specifies an invalid memory scope? The system returns a clear error so the Agent can correct the call.
- What happens when archival memory exceeds the configured size limit? Only the oldest archival entries are removed; core memories are never affected.
- What happens if a backend switch occurs between save and recall? The behavior depends on the backend currently active; the abstraction boundary keeps calling code unchanged.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST expose a unified long-term memory facade that the rest of the runtime can use to build context, store memories, and recall memories.
- **FR-002**: The facade MUST allow a memory to be stored with an explicit scope that distinguishes "always present" facts from "archivable" facts.
- **FR-003**: The facade MUST allow memories to be recalled by keyword, returning only matching archival memories.
- **FR-004**: The system MUST provide a default backend that stores memories in a human-readable file with clear core and archival sections.
- **FR-005**: The system MUST provide a structured backend that stores memories as individual records, supporting volume growth without changing the facade contract.
- **FR-006**: The system MUST provide an external integration backend that delegates storage and semantic retrieval to a self-hosted service, keeping the same facade contract.
- **FR-007**: All backends MUST read fresh data on every context build so that memories saved in one turn are visible in the next turn.
- **FR-008**: All backends MUST preserve core memories in full and apply any size limits only to archival memories.
- **FR-009**: The system MUST expose save and recall capabilities as built-in tools that the Agent can invoke during the ReAct loop.
- **FR-010**: The tool layer MUST be backend-agnostic; the same save/recall tools work regardless of which backend is active.
- **FR-011**: The system MUST integrate the long-term memory facade into the existing prompt assembly flow so that relevant memories are automatically included in the Agent's working context.

### Key Entities *(include if feature involves data)*

- **Memory Entry**: A single fact or observation worth remembering. Attributes include content, scope (core or archival), and creation time.
- **Core Memory Section**: Memories that must always be included in the Agent's working context in full.
- **Archival Memory Section**: Memories that are searchable and subject to size limits when included in context.
- **Memory Backend**: A pluggable implementation responsible for persisting and retrieving memory entries while honoring the core/archival contract.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A memory stored during a conversation is available in the Agent's context in the next turn 100% of the time across all supported backends.
- **SC-002**: Switching the active backend requires only a configuration change and zero code changes in the ReAct loop, prompt builder, or tool definitions.
- **SC-003**: Core memories are never truncated; at least 99% of archival memory truncation tests show that only the oldest archival entries are removed.
- **SC-004**: The save and recall tools are available to the Agent regardless of backend, and their public names and descriptions remain stable.
- **SC-005**: 100% of contract tests pass against every supported backend using the same assertion set.

## Assumptions

- The Agent is responsible for deciding when to save a memory and which scope to use; the system does not automatically extract memories from conversation history.
- The external memory backend requires a separately deployed self-hosted instance; it is not bundled with the runtime.
- The existing session manager continues to own short-term conversation history; this feature focuses on long-term memory only.
- The prompt assembly flow already has a defined integration point for injecting a memory section.
