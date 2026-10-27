# Contract: MemoryService Facade

**Scope**: Public facade used by `PromptBuilder`, `ReActLoop`, and `MemoryTools`.

## Interface

```java
public interface MemoryService {
    /** Build the long-term memory section to inject into the prompt context. */
    String buildContext(Session session);

    /** Store a memory under the given scope. */
    void remember(String content, MemoryScope scope);

    /** Recall archival memories matching the keyword. */
    List<String> recall(String keyword);
}
```

## Semantics

- `buildContext` returns core memories in full plus archival memories after truncation.
- `remember` appends the content; the backend decides exact persistence.
- `recall` never searches core memories; it searches only the archival section.
- All operations read fresh data; callers must not assume caching.

## Integration

`MemoryService` also implements `com.sableos.core.memory.MemoryProvider`:

```java
public interface MemoryProvider {
    String memorySection(Profile profile);
}
```

`memorySection` delegates to the same load logic as `buildContext` but is expressed in terms of `Profile` so `PromptBuilder` can consume it without knowing `Session`.
