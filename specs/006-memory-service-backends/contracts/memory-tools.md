# Contract: MemoryTools Built-in Tools

**Scope**: Tools exposed to the Agent for reading and writing long-term memory.

## save_memory

- **Name**: `save_memory`
- **Description**: "记住一件值得长期记住的事"
- **Parameters**:
  - `content` (string, required): the fact to remember.
  - `scope` (string, optional): `"core"` or `"archival"`; defaults to `"archival"`.
- **Returns**: confirmation string or an error message if scope is invalid.

## recall_memory

- **Name**: `recall_memory`
- **Description**: "按关键词检索长期记忆"
- **Parameters**:
  - `keyword` (string, required): the keyword to search for.
- **Returns**: matching archival memories joined by newlines, or `"没有找到相关记忆"` if none match.

## Registration

`MemoryTools` is authored in `sableos-memory` and registered into `sableos-tool`'s `ToolRegistry` by `sableos-boot` assembly using `registerAnnotated(Object)`.
