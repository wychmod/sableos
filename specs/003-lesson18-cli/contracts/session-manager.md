# Contract: SessionManager（会话身份与持久化唯一收口）

> 契约在 `sableos-core`（`com.sableos.core.session`），JPA 实现 `JpaSessionManager` 在 `sableos-storage`（依赖倒置，research D-01）。
> 沿用 §28 `ScheduledTaskStore` 先例。`SessionManager extends SessionStore`（追加式，`SessionStore` 零改动）。

## 接口

```java
package com.sableos.core.session;

public interface SessionManager extends SessionStore {   // SessionStore: void save(Session)

  /** session_id 单点拼接公式（全代码库唯一实现处；research D-02 / 不变量 H4④）。 */
  static String sessionId(String channel, String userId, String profileName) {
    return channel + ":" + userId + ":" + profileName;
  }

  /** 按三元组取回既有会话或新建（幂等：同三元组收敛同一 session_id）。 */
  Session getOrCreate(String channel, String userId, String profileName);

  /** 按会话标识取回（不存在返回 empty）。 */
  java.util.Optional<Session> get(String sessionId);

  // void save(Session) 继承自 SessionStore：序列化历史后 upsert 到 sessions 表
}
```

## 行为契约

| 方法 | 契约 | 关联 |
| --- | --- | --- |
| `sessionId(c,u,p)` | 返回 `c:u:p`；**静态、无副作用、唯一实现处**；任何入口不得另行拼接 | FR-008 / SC-002 / H4④ |
| `getOrCreate(c,u,p)` | `id=sessionId(c,u,p)`；命中→`Session.rehydrate(...)`（带历史与原始时间戳）；未命中→新 `Session(id,p,c,u)`（空历史）。同三元组历次调用返回**同一** id | FR-006/007 / SC-001 |
| `get(id)` | 命中→rehydrate；未命中→`Optional.empty()` | FR-006 |
| `save(session)` | **upsert**：命中既有行只更新 `messages_json`/`status`/`last_active_at`；未命中则从 `Session` 补全整行插入（sableos 领域 `Session` 自带 channel/userId/status/createdAt，比 oryxos 瘦 Session 富，故可 upsert 而非 oryxos 的"必须先 getOrCreate 否则抛"）。`AgentService.process` 保存路径无需强制先 getOrCreate | FR-009/010 / SC-003 |

## 幂等与隔离（harness 钉死）

- 幂等：`getOrCreate("cli","wang","default")` 两次 → `first.id().equals(second.id())`（且指向同一持久行）。
- 隔离：`getOrCreate("web","wang","default").id()` ≠ `getOrCreate("cli","wang","default").id()`（channel 不同即不同会话）；user/profile 任一不同同理。
- 单点：id 只由 `sessionId(...)` 生成；`SessionManagerTest` 断言 id 形态 == `channel:user:profile`，收尾 grep 核验无第二处拼接。

## 装配（boot）

`ReactConfiguration.sessionStore()` 由返回 `InMemorySessionStore` 改为返回 `JpaSessionManager`（`SessionManager` bean）；`AgentService(profileRegistry, reActLoop, sessionStore)` 构造不变，`process` 内 `sessionStore.save(session)` 落库。`InMemorySessionStore` 删除（research D-08，软门禁 #4，停点确认）。

## 边界

- 不做归档状态机流转（status 恒 active）；不做按条拆表；不做会话查询接口（`session list` 轻命令直读 DB，不经此契约）。
- `getOrCreate` 未命中时是否立即 insert 空行由实现定，但须保证并发下同三元组收敛同一 id（主键幂等兜底）。
