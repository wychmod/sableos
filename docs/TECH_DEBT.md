# 技术债与待办清单

> **用途**：记录已知但**有意暂缓**的实现问题。每条都带「验收判据」和「自检命令」，任何人（或 AI）在后续迭代中都能客观判断是否已解决，不需要重新推导。
> **维护约定（给后续迭代的人/AI）**：
> 1. 判定为已解决时，把对应的 `- [ ]` 改成 `- [x]`，并在行尾补 `（解决于 第N节 / <commit>）`。
> 2. 同步更新下方「索引」表的 `状态` 列。
> 3. 在文末「变更记录」追加一行：日期 + 编号 + 一句话。
> 4. **不要**在问题仍然存在时勾选——勾选即代表「已有测试或配置能证明它不会发生」。
> **状态图例**：`暂缓`（已知未修，风险休眠）、`进行中`、`已解决`、`不修`（附理由）

## 索引

| 编号     | 标题                          | 类型      | 严重性 | 状态    | 必须处理的时间点              |
| ------ | --------------------------- | ------- | --- | ----- | --------------------- |
| TD-001 | 单会话并发：同一 `session_id` 的并发读写 | 缺陷      | 高   | 进行中 | 第 26 节 Web Service 落地前 |
| TD-002 | 会话身份无「实例」维度，缺「新建会话」能力     | 设计缺口    | 中   | 暂缓  | 支持「并行多对话」时           |
| TD-003 | 单实例假设：条纹锁 / 调度器 / 内存态 Registry / SQLite 单写者 | 设计缺口 | 中 | 暂缓 | 多副本部署前 |
| TD-004 | HTTP 工具曾跟随 30x 重定向绕过域名白名单（已修） | 缺陷 | 高 | 已解决 | —（2026-11-03 已修，附回归测试） |

---

## TD-001 · 单会话并发：同一 `session_id` 的并发读写

**记录日期**：2026-10-26 ｜ **发现于**：第 18 节代码走读（`JpaSessionManager`）｜ **决定**：先不改（风险当前休眠）｜ **最近复核**：2026-11-03（应用层已缓解，见下）

### 一句话

`JpaSessionManager.getOrCreate` / `save` 把「读-判-写」当成原子操作，且用**整段 JSON 覆盖**表达「追加历史」，因此当多个写者指向同一个 `session_id` 时会静默丢历史、或撞主键报错。

### 风险判据（最容易误判的一点）

会话身份由**三元组**决定，不由「用户打开了几个窗口/会话」决定：

```text
session_id = channel : user : profile
```

| 场景                              | 持久化层看到的结果               | 是否有并发风险 |
| ------------------------------- | ----------------------- | ------- |
| 两个终端，`--profile chat` / `--profile code` | 两个不同主键行                 | **无**（安全） |
| 两个终端，都 `--profile default`（同 OS 用户） | **其实是同一个会话**，两流灌同一行 | **有**（且用户无感） |
| 一个 CLI + 一个 Web，同 user 同 profile | 两个不同主键行（channel 不同）    | **无**（安全） |
| Web 端同一用户两个标签页并发请求              | 同一个会话                   | **有**   |

> 结论：真正的判据是「**有几个写者指向同一个 `session_id`**」，不是「开了几个会话」。两个真正不同的会话是安全的；危险的是「以为开了两个、其实是一个」。

### 为什么现在不炸（**已过时，请读下文「2026-11-03 复核」**）

> 下面这段是 2026-10-26 的论证，其前提（单进程、单线程、单一入口）在后续章节已被打破。保留原文是为了留下判断依据的演变，**不要把它当现状**。

`getOrCreate` 的唯一生产调用点是 `CliChannel.run`，位于 `while` 循环**外面**，只调一次；`save` 的唯一调用点是 `AgentService.process`，每轮一次。单进程、单线程、逐行串行 → 天然无并发。**任何单请求的手工测试都复现不出来。**

### 2026-11-03 复核：应用层已缓解，文档原先未销账

上面「单线程单入口」的前提**已经不成立**——现在有 CLI、Web（`/api/v1/sessions/{id}/messages`）、
定时任务（`AgentScheduler`）三个入口都会调到 `AgentService.process`，并发是真实存在的。

实际上 TD-001-B（lost update）**已在应用层修复**，只是当时没登记到这里：

| 项 | 状态 | 证据 |
| --- | --- | --- |
| **B 写入路径 lost update** | ✅ **已缓解**（应用层） | `AgentService` 用 64 条条纹锁串行化同一会话：`LOCK_STRIPES = 64`、`lockFor(session.id())`、锁内 `refreshFromStore()` 对齐快照。提交 `1a0aa45 fix(agent): 修复同一会话并发请求丢失更新的问题` |
| **C SQLite busy_timeout** | ✅ **已解决** | `application.yaml` 的 datasource url 已带 `busy_timeout: 5000`，与 `LightDbConfig` 一致 |
| **A 创建路径 TOCTOU** | ⚠️ **仍有理论窗口** | `getOrCreate` 的「读-判-写」未加锁；但 Web/CLI 都先经 `AgentService` 取锁再进入，实际暴露面已大幅缩小 |
| **DB 层兜底（`@Version` / append-only）** | ❌ 仍缺 | 字段层面无乐观锁；当前依赖应用层锁，**只在单实例成立** |
| **并发验收测试** | ❌ 仍缺 | `sableos-storage` 下无 `*concurren*` 测试 |

**结论：TD-001 从「三重缺陷全开」降级为「应用层已缓解，DB 层兜底与并发验收测试仍缺」。**
若将来多实例部署，应用层条纹锁失效（锁在进程内），B 会立即复现——这是必须先补 DB 层兜底或状态外置的地方。

### 三层缺陷与验收判据

#### TD-001-A · 创建路径 TOCTOU（并发首次获取同三元组）

- [ ] **已解决**：`getOrCreate` 的插入失败能被兜住并回查到既有行，不再向调用方抛主键冲突

**现象**：`findById` 判空 → `save` 插入，两步非原子。两线程同时「看不到」该行 → 都插入 → 后者撞 `sessions.session_id` 主键（SQLite 下也可能是 `SQLITE_BUSY`）。

**JPA 细节（易漏）**：`SessionEntity` 是赋值型 `@Id`（无 `@GeneratedValue`），`SimpleJpaRepository.save()` 的 `isNew()` 退化为 `id == null` → 实际走 `em.merge()`（先 SELECT 再 INSERT），插入路径实为 3 条语句，竞态窗口比直觉更宽。

**验收判据**：存在一个并发测试（建议 `SessionConcurrencyTest`），N 个线程对同一三元组并发 `getOrCreate`，断言：全部成功、返回同一 `id`、库中恰好 1 行。

#### TD-001-B · 写入路径 lost update（并发写同一会话）

- [ ] **已解决**：同一会话的并发轮次不再丢失彼此的消息（有测试证明）

**现象**（最严重）：`save()` 是「整段读 → 整段改 → 整段写」，`messages_json` 整体覆盖。两个写者各自持有旧历史 → 后写者覆盖先写者，新增消息静默蒸发，**无任何报错**。

**放大器**：`CliChannel.run` 在循环外取一次 `Session`，整场对话持有同一份内存副本 → 陈旧窗口 = **整场对话的时长**，不是毫秒级。

**验收判据**：存在一个测试，模拟两个写者各自 append 后 save，断言两条消息**都在**库中（或明确以乐观锁冲突 + 重试的方式收敛）。

#### TD-001-C · SQLite 层未给并发留余地

- [ ] **已解决**：重命令数据源已配置 `busy_timeout`（与原计划一致），且与轻命令口径一致

**现象**：`application.yaml` 的 `url: jdbc:sqlite:${workspace.root:.sableos}/sableos.db` **未带 `busy_timeout`**，SQLite 默认 busy timeout 为 0 → 并发写立即 `SQLITE_BUSY: database is locked`。而轻命令 `LightDbConfig` 反而带了 `?busy_timeout=5000`，两处不一致。

**验收判据**：`application.yaml` 的 datasource url 含 `busy_timeout`，且与 `LightDbConfig` 取值一致（可考虑抽常量/配置项）。

### 自检命令（判断是否已解决）

```bash
# A：是否已有冲突兜底（无输出 = 未解决）
grep -rn "saveAndFlush\|DataIntegrityViolationException" sableos-storage/src/main/java/

# B：是否已有乐观锁 / 事务边界（无输出 = 未解决）
grep -rn "@Version\|@Lock" sableos-storage/src/main/java/

# C：数据源是否配了 busy_timeout（无输出 = 未解决）
grep -n "busy_timeout" sableos-boot/src/main/resources/application.yaml

# 是否有并发验收测试（无输出 = 未解决）
ls sableos-storage/src/test/java/com/sableos/storage/ | grep -i "concurren"
```

> 2026-10-26 基线：上述四条**全部无输出**，故 TD-001-A/B/C 均未解决。
>
> **2026-11-03 复核结果**：C 项已命中（`busy_timeout: 5000` 已配）→ **C 已解决**；B 项仍无 `@Version`/`@Lock`，
> 但 lost update 已由 `AgentService` 的应用层条纹锁缓解（grep 看不到，需读 `AgentService`）；
> A 项仍无 `saveAndFlush` 兜底；并发验收测试仍缺。**故当前状态为：B 应用层缓解、C 已解决、A 与 DB 层兜底仍开放**。
>
> **注意**：上面的 grep 只是**启发式**——命中通常意味着「很可能已修」，但**没命中不等于没修**（例如 B 若改用 append-only SQL 或 turn coordinator，就不会出现 `@Version`）。**权威判据永远是验收测试**：测试能在并发下证明不丢消息、不撞主键，才算真正解决。

### 修复方向（已论证，届时直接照做）

**A（创建路径）**：改成「先插、冲突再回查」

```java
try {
  repository.saveAndFlush(entity);          // 必须 flush，否则异常要等到提交才抛
  return restore(entity);
} catch (DataIntegrityViolationException e) {
  return repository.findById(id).map(this::restore)
      .orElseThrow(() -> new IllegalStateException("会话创建冲突但回查不到: " + id, e));
}
```

> **关键陷阱**：**不要**给 `getOrCreate` 加 `@Transactional`。插入失败后事务已被标记 rollback-only，捕获异常再 `findById` 也无效（提交时照样炸）。要么不加事务（每次 repo 调用各自独立事务），要么把插入放 `REQUIRES_NEW` 再在外层重试。

**B（写入路径，治本，三选一）**
1. 轻量：加 `@Version` 乐观锁列（需 V5 迁移）+ 冲突后「重读 → 重放本轮增量 → 重写」；
2. 或改 append-only（JSON 数组原子追加的 SQL / 按条插表），不再整段覆盖；
3. 或「每会话单写者」的 turn coordinator（oryxos 有该组件），把并发请求排队到同一写者。

**C（进程内串行，最便宜）**：`ConcurrentHashMap<String, ReentrantLock>` 按 `sessionId` 锁住「取会话 → 跑一轮 → 保存」。
> **不能用 `synchronized`**：JDK 21 下阻塞调用会把虚拟线程钉在载体线程上（AGENTS.md §5.2）。跨进程无效。

**D（SQLite）**：datasource url 补 `?busy_timeout=5000`（对齐 `LightDbConfig`），并考虑 `PRAGMA journal_mode=WAL` 让读写不互斥。

### 与 spec 的关系

第 18 节 spec 的 Edge Case 已明确要求「同一三元组在并发下同时首次获取须保证收敛到同一会话，不产生两条并行新建的历史」。当前实现**未满足**该项——所以这是**真实债务**，不是超出范围的新需求。

---

## TD-002 · 会话身份无「实例」维度，缺「新建会话」能力

**记录日期**：2026-10-26 ｜ **类型**：设计缺口（非缺陷）｜ **决定**：留待单独立项

### 一句话

`session_id` 里没有「会话实例」这一维，所以 `一个 user + 一个 profile + 一个 channel` **永远只有一条会话**，从第一次到最后一次 `chat` 全部累积在同一行；「同时开两个对话」在语义上表达不出来。

### 现状证据

- `SessionManager.sessionId` 公式只有三个入参，全仓无 threadId / conversationId 概念；
- 全仓无 `/new` 命令（grep 零命中）；
- `CliChannel` 每次启动都 `getOrCreate` 到同一条会话 → 第二次 `chat` 不是新开，而是**接着上次聊**。

### 连带影响

1. **历史只增不减**：`messages_json` 一整段 JSON，每次 `save` 全量读 + 全量写，聊得越久单行越大、往返越贵；
2. **冲突面随时间放大**：撞车概率不变，但每次撞车丢失的「整段历史」随时间线性增长（放大 TD-001-B 的损失）。

### 若要支持「并行多对话」

- [ ] **已决策并落地**：会话身份粒度方案已定，且有对应 spec

**正确做法不是加锁**（锁只能排队，不能分开），而是给身份加「实例」维度：

| 方案    | 做法                                                        | 代价                                                       |
| ----- | --------------------------------------------------------- | -------------------------------------------------------- |
| 彻底版   | `session_id = channel:user:profile:threadId`              | **H4④ 公式契约变更**：改 core `SessionManager.sessionId`、所有入口、迁移兼容、`session list` 口径，必须走 spec |
| 折中版   | 保留现公式，加 `/new`：新建时派生新 id（仍由 `SessionManager` 单点生成）        | 改动小，不破坏「拼接只此一处」；但 `session list` 需能区分同一 profile 的多条      |
| 不做    | 保持「粘性单会话」，仅在文档中写明这是有意设计                                   | 需接受 TD-001-B 的损失随历史增长                                   |

> 在 TD-002 决策之前，TD-001 的 A + C 仍应照做——它们不依赖会话粒度方案。

---

## TD-003 · 单实例假设：五处结构在水平扩展时集体失效

**记录日期**：2026-11-03 ｜ **发现于**：全项目审计 ｜ **决定**：暂缓（产品定位就是单机）

### 一句话

多处核心结构直接建立在「单进程」前提上。单机跑没问题，一旦部署两个副本就会同时出现定时任务双跑、
同一会话丢更新、Agent 目录不同步。**这不是当前缺陷**——但它决定了「加机器」不能作为扩容手段。

### 清单与失效后果

| # | 结构 | 位置 | 多副本下的后果 |
| --- | --- | --- | --- |
| 1 | 64 条条纹锁（`ReentrantLock`） | `AgentService.LOCK_STRIPES` | 锁在进程内，同一会话的并发请求落在两个实例上 → lost update 复现（TD-001-B） |
| 2 | 调度器 `ConcurrentHashMap<Long, ScheduledFuture>` | `AgentScheduler` | 无分布式协调，两个实例各自跑同一份 cron → **定时任务双跑** |
| 3 | SQLite 单写者 | `application.yaml` datasource | 并发写靠 `busy_timeout: 5000` 兜底，超时即失败；无法分库 |
| 4 | `ProfileRegistry` 内存态 + `WatchService` | `AgentService` / Agent 目录监听 | Agent 变更不跨实例传播，各副本看到不同目录 |
| 5 | `TraceContext` 的 `ThreadLocal` | `TraceContext.CURRENT` | 跨实例链路需靠 traceparent 传播（已有 `TraceParent` 类，说明考虑过） |

### 验收判据（什么时候算解决）

- [ ] 会话串行化改为状态外置（DB 行锁 / 分布式锁 / turn coordinator），不再依赖进程内锁
- [ ] 调度器接入分布式调度或选主，同一 cron 全网只跑一次
- [ ] 数据源换成支持多写者的库，或明确分片方案
- [ ] `ProfileRegistry` 改为共享存储 + 变更广播
- [ ] 有多副本集成测试能证明上述不变量（至少覆盖 #1 与 #2）

### 现在为什么不处理

产品定位是「个人 / 企业私有单机 Agent OS」，单副本是正常形态而非降级。
登记此条的目的不是催修，而是**防止将来有人以为「加两台机器就能扛更多」**——
真要走分布式，这五处是必须先解决的地基，不是运维调优能绕过的。

> 与 TD-001 的关系：TD-001 是单实例内的并发丢更新（已应用层缓解）；TD-003 #1 是它在多实例下失效的原因。

---

## TD-004 · HTTP 工具跟随 30x 重定向绕过域名白名单（SSRF）

**记录日期**：2026-11-03 ｜ **发现于**：全项目审计 ｜ **状态**：✅ 已解决

### 一句话

`http_get` / `http_post` / `notify` 只在发请求前校验一次域名白名单，而底层 `HttpURLConnection`
默认跟随重定向，导致白名单内主机用 302 即可把请求引到任意内网地址，沙箱形同虚设。

### 原缺陷与实测证据

`ToolConfiguration` 原用 `SimpleClientHttpRequestFactory`（基于 `HttpURLConnection`），
其实例级 `getInstanceFollowRedirects()` 默认返回 `true`。实测：白名单主机返回 `302` → `Location: http://127.0.0.1:18889/...`，
客户端自动跟过去并返回目标内容 `INTERNAL_SECRET_LEAKED`，全程未再过白名单。

值得注意的是 provider 层**已经**正确禁用了重定向（`ProviderChatModelFactory`、`HttpProviderHealthProbe`
均为 `HttpClient.Redirect.NEVER`），只有工具层漏了同一处理。

### 修复

`ToolConfiguration.restClient` 改用 JDK `HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)`
+ `JdkClientHttpRequestFactory`，由客户端统一设定，一处生效。

### 验收判据

- [x] 白名单主机 302 到另一端口时，目标服务**一次都不被访问**
- [x] `HttpClientRedirectTest` 用原子计数器直接断言「目标服务 0 次命中」，删掉修复后该测试失败（已实测）

---

## 变更记录

| 日期         | 编号     | 变更                                        |
| ---------- | ------ | ----------------------------------------- |
| 2026-10-26 | TD-001 | 建档。三层缺陷（A TOCTOU / B lost update / C SQLite 配置）均未解决，决定暂缓 |
| 2026-10-26 | TD-002 | 建档。设计缺口，待单独立项                             |
| 2026-11-03 | TD-001 | 复核并销账：B 已由 `AgentService` 条纹锁在应用层缓解（`1a0aa45`）、C 的 `busy_timeout` 已配置；更新「为什么现在不炸」的过时论证；A 与 DB 层兜底、并发验收测试仍开放 |
| 2026-11-03 | TD-003 | 新建档：单实例假设清单（条纹锁/调度器/内存态 Registry/SQLite 单写者）——多副本部署前必须先解决 |
| 2026-11-03 | TD-004 | 新建档：HTTP 工具曾跟随 30x 重定向绕过域名白名单（已修，附回归测试） |
