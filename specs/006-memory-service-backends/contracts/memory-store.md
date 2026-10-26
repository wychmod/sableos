# Contract: LongTermMemoryStore Backend

**Scope**: Pluggable backend interface behind `MemoryService`.

## Interface

```java
public interface LongTermMemoryStore {
    /** Append content to the given scope. */
    void append(String content, MemoryScope scope);

    /** Return core section in full + archival section after truncation. */
    String load();

    /** Search archival memory for entries containing the keyword. */
    List<String> recallByKeyword(String keyword);
}
```

## Behavioral Contract

All implementations MUST honor:

1. **No caching**: every `load()` and `recallByKeyword()` reads from the underlying store.
2. **Core immutable**: core memories are returned in full; truncation never touches them.
3. **Explicit scope**: writes are routed to the scope provided by the caller.
4. **Archival-only recall**: keyword search only scans the archival section.

## Backends

- `MarkdownMemoryStore` — file backend, default.
- `SqliteMemoryStore` — row backend in `memory_entries`.
- `Mem0MemoryStore` — REST backend to self-hosted Mem0.
