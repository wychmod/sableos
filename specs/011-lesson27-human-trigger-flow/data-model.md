# Data Model: 人推链路串联

**Feature**: `011-lesson27-human-trigger-flow` | **Date**: 2026-10-31

## 结论先行

本节**不新增表、不新增列、不改 Flyway 迁移**。唯一的"新数据结构"是一个只读投影视图（会话摘要），不落库。

## 既有表（本节读写的三张）

| 表 | 本节用途 | 变化 |
| --- | --- | --- |
| `sessions` | 会话与历史；列表按 `last_active_at` 倒序取摘要 | 无结构变化，新增按 `status` 过滤的查询 |
| `llm_calls` | 每次模型调用一条，成功失败都写 | 无变化 |
| `tool_invocations` | 每次工具调用一条，含 `success` / `error_message` | 无变化 |

## 新增：`SessionSummaryView`（只读投影，不落库）

| 字段 | 类型 | 来源 | 说明 |
| --- | --- | --- | --- |
| `id` | string | `sessions.session_id` | `channel:user:profile` |
| `profileName` | string | `sessions.profile_name` | |
| `channel` | string | `sessions.channel` | web / cli / scheduler |
| `userId` | string | `sessions.user_id` | |
| `status` | string | `sessions.status` | active / archived |
| `createdAt` | string (ISO-8601) | `sessions.created_at` | |
| `lastActiveAt` | string (ISO-8601) | `sessions.last_active_at` | 列表排序键 |
| `messageCount` | int | 反序列化 `messages_json` 后的条数 | **不带**正文 |

与 `SessionView` 刻意分开：列表一次返回最多 100 条，若复用带正文的视图，响应体会随对话长度线性膨胀。

## 查询口径

```text
listRecent(limit, status)
  limit <= 0                 -> []
  status 为 null/空白        -> find all, sort lastActiveAt desc, take limit
  否则                       -> findByStatus(status), sort lastActiveAt desc, take limit
```

过滤、排序、截断**三者在 SQL 侧完成**；不得"先取 N 条再内存筛"（会让 `status=archived` 的结果数量被未命中行吃掉）。

## 边界

- `messageCount` 由已存历史计算，不回填、不新增冗余列。
- 归档是软归档（`status=archived` + 首次写 `archived_at`），列表默认不过滤状态，两种状态都会出现。
