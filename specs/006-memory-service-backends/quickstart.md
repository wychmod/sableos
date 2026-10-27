# Quickstart: Memory Service Backends

**Feature**: Memory Service Backends
**Date**: 2026-10-16

## Prerequisites

- JDK 21
- Maven 3.8+
- Existing SableOS workspace (created by `sableos init`)

## Build

```bash
mvn -q -B clean verify -Dowasp.skip=true
```

Expected: all default tests pass, including the parameterized `MemoryStoreContractTest`.

## Run unit tests for this feature

```bash
mvn -q -B test -pl sableos-memory -Dowasp.skip=true
```

Expected: `MemoryStoreContractTest`, `MarkdownMemoryStoreTest`, `SqliteMemoryStoreTest`, `Mem0MemoryStoreTest`, `MemoryToolsTest`, `MemoryServiceImplTest` all green.

## Manual backend-switch smoke test

### Markdown backend (default)

```bash
java -jar sableos-boot/target/sableos-boot-*.jar chat --profile default
```

In the chat:

```text
You: 记住我的名字叫小王
Agent: (calls save_memory) 已记住
You: 我叫什么
Agent: 你叫小王
```

Verify `.sableos/memory/MEMORY.md` contains an archival entry.

### SQLite backend

Edit `application.yaml`:

```yaml
memory:
  backend: sqlite
```

Repeat the chat. Verify `memory_entries` table has rows:

```bash
sqlite3 .sableos/sableos.db "SELECT scope, content FROM memory_entries;"
```

### Mem0 backend: Platform or self-hosted OSS

`memory.mem0.mode` 决定接口形态，两者路径与鉴权不同，不能只改 base URL。

**Platform（默认）**

```bash
export MEM0_API_KEY=...          # Mem0 dashboard 获取
```

```yaml
memory:
  backend: mem0
  mem0:
    mode: platform               # 默认值，可省略
    # base-url: https://api.mem0.ai
```

**自托管 OSS server**

```bash
cd server && make bootstrap      # 或 docker compose up -d；API 在 http://localhost:8888
export MEM0_API_KEY=m0sk_...     # dashboard 的 API Keys 页创建；AUTH_DISABLED=true 时可留空
```

```yaml
memory:
  backend: mem0
  mem0:
    mode: oss
    base-url: http://localhost:8888
```

Repeat the chat and verify entries appear in the corresponding Mem0 store (user id is `sableos:<profile>`).

## Expected end-to-end behavior

- After `save_memory`, the next prompt build includes the memory.
- `recall_memory` returns only archival matches.
- Core memories are never dropped when archival memory grows.
