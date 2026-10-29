# Sandbox 白名单管理接口设计（增删改查）

**状态**：需求与设计稿（待排期）｜ **涉及模块**：`sableos-core`、`sableos-storage`、`sableos-tool`、`sableos-web`、`sableos-boot` ｜ **前置**：第 24 节 `WhitelistSandbox`

> 本文不是 Spec-Kit 的 feature 产物，而是需求与设计稿：讲清"为什么做、做成什么样、怎么验收、跨平台怎么保证"。白名单 CRUD 不在 16~31 节课件范围内，进入实施时需按 `docs/class/Spec-Kit 执行指导` 开 feature，并把本文作为 `specify` 的输入；新增的 REST 路径、配置键、数据表与 public 类型属于新的对外概念，需先确认范围再动手。

## 1. 背景与目标

### 1.1 背景

第 24 节交付的白名单是**启动时绑定、运行期只读**的：

- `FileSandboxProperties` / `ShellSandboxProperties` / `HttpSandboxProperties` 从 `application.yaml` 读取三项配置；
- `WhitelistSandbox` 在构造器里把三项配置转成不可变字段（`allowedRoots` / `allowedCommands` / `allowedDomainPatterns`），`enforce` 只读这些字段；
- 结果：运营方要改白名单，必须改 YAML、重启进程；没有运行时增删改查，也没有管理台入口。

### 1.2 目标

1. 提供白名单的 REST 增删改查接口，改动**立即生效**（不需要重启）。
2. 条目持久化到 SQLite，重启后仍在（**已确认：加 DB，DB 为运行时事实源**）。
3. Windows / macOS / Linux 行为一致且可验证。
4. 不改变 `Sandbox` 接口、不改变工具侧 `enforce` 调用点、不改变审计路径。
5. 用 harness 把 CRUD、运行时刷新、跨平台规则钉死。

### 1.3 非目标

- 不做容器 / microVM 隔离；
- 不做认证、RBAC、多租户（核心阶段 Web Service 本身无认证，见 `TechnicalSolution.md` §7.5）；
- 不做多实例同步（核心阶段是单二进制单实例）；
- 不做 SMTP 端点校验（`EmailNotifyAdapter` 属扩展阶段）；
- 不做 Profile 级 Tool Policy；
- 不写回 `application.yaml`（不修改 YAML 文件）。

## 2. 现状与差距

| 现状 | 位置 | 差距 |
| --- | --- | --- |
| 白名单只读、启动绑定 | `sableos-tool/src/main/java/com/sableos/tool/sandbox/WhitelistSandbox.java` | 无运行时刷新入口 |
| 三项配置是 record | `FileSandboxProperties.java` / `ShellSandboxProperties.java` / `HttpSandboxProperties.java` | 只用于启动装配，无持久化 |
| 装配启用属性 | `sableos-boot/.../tool/ToolConfiguration.java:44-49`（`@EnableConfigurationProperties`） | 不感知 CRUD |
| 统一响应信封与异常 | `sableos-web/.../common/ApiResponse.java`、`GlobalExceptionHandler.java` | 可直接复用；`sableos-web` 目前尚无 Controller（26 节未做） |
| 最新迁移是 V4 | `sableos-storage/src/main/resources/db/migration/{sqlite,postgresql}/V4__memory_entries.sql` | 需要新增 V5 |
| CI 只有 Ubuntu | `.github/workflows/ci.yml` | 缺少 Win/macOS 兼容矩阵 |

## 3. 设计原则

1. **接口先行不变**：`Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` 逐字不改；只给 `WhitelistSandbox` 增加包内可见的刷新能力。
2. **无锁读**：`enforce` 每次从 `AtomicReference<WhitelistPolicy>` 取一次不可变快照，不在校验路径上加锁、不做阻塞调用（AGENTS §5.2）。
3. **单一写者**：只有管理服务写库并换快照；工具执行只读快照。
4. **DB 为运行时事实源（已确认）**：白名单条目落 SQLite（`sandbox_whitelist_entries`），运行时以数据库为准；`application.yaml` 只在**首次启动**播种一次（seed-once），之后 DB 是唯一事实源，见 §5.3。
5. **管理接口是高权限面**：新增 `sableos.sandbox.admin.enabled`，按产品决策**默认开启**；因此必须配合回环绑定或认证反向代理，启动时强制 WARN（见 §8）。
6. **跨平台靠"存储可移植 + 运行时按本机解释"**：不按 OS 过滤条目；同一份配置里不同 OS 的条目并存、未命中的条目天然失效。
7. **Harness 先行**：先写校验规则与刷新测试，再写服务、Controller、集成测试；跨 OS 用 CI 矩阵兜底。

## 4. 总体架构

### 4.1 分层与依赖方向

```text
sableos-web       SandboxWhitelistController（只依赖 core 接口）
      │
      ▼
sableos-core      WhitelistType / WhitelistEntry / WhitelistCommand
                  SandboxWhitelistAdmin / SandboxWhitelistStore
                  领域异常（校验 / 未找到 / 冲突）
      ▲                     ▲
      │                     │
sableos-tool        sableos-storage
DefaultSandboxWhitelistAdmin   SandboxWhitelistEntryEntity
WhitelistRules                 SandboxWhitelistEntryRepository
WhitelistSandbox.refresh       JpaSandboxWhitelistStore
      ▲
      │
sableos-boot     装配 + 首次启动播种（ApplicationRunner）
```

依赖方向保持 `web → core`、`tool → core`、`storage → core`、`boot → 全部`，不新增模块、不产生环。`sableos-tool` **不依赖** `sableos-storage`，通过 core 的 `SandboxWhitelistStore` 接口倒置。

### 4.2 运行时快照

```java
// WhitelistSandbox 内部（不进入 Sandbox 接口）
private final AtomicReference<WhitelistPolicy> policy;

record WhitelistPolicy(
    List<Path> allowedRoots,
    Set<String> allowedCommands,
    List<String> allowedDomainPatterns) { /* 不可变拷贝 */ }

void refresh(WhitelistPolicy newPolicy) {   // 包内可见，由管理服务调用
  policy.set(newPolicy);
}

@Override
public void enforce(SandboxAction action) {
  WhitelistPolicy current = policy.get();   // 一次调用只读一次，拿到一致快照
  // 后续校验全部基于 current
}
```

- 构造器仍按三项配置生成初始策略，保持第 24 节行为不变。
- CRUD 成功后，管理服务从数据库重建 `WhitelistPolicy` 并 `refresh`；失败不换快照。
- 不用 `synchronized`、不用 `ReentrantLock` 的阻塞区、不用 Reactor/`CompletableFuture`；`AtomicReference.set` 是无锁发布。

## 5. 数据模型与迁移

### 5.1 表结构

两张表：`sandbox_whitelist_entries`（条目）与 `sandbox_whitelist_seed`（播种标记）。SQLite 与 PostgreSQL 双轨脚本，同一个迁移版本 **V5**。

```sql
-- SQLite: V5__sandbox_whitelist.sql
CREATE TABLE IF NOT EXISTS sandbox_whitelist_entries (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    type        VARCHAR(32)   NOT NULL,
    value       VARCHAR(1024) NOT NULL,
    description VARCHAR(255),
    created_at  INTEGER       NOT NULL,
    updated_at  INTEGER       NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_sandbox_whitelist_type_value
    ON sandbox_whitelist_entries (type, value);

-- 播种标记：单行表，行存在即"已播种过"，与条目是否为空无关（理由见 §5.3）
CREATE TABLE IF NOT EXISTS sandbox_whitelist_seed (
    id          INTEGER  PRIMARY KEY CHECK (id = 1),
    seeded_at   INTEGER  NOT NULL,
    entry_count INTEGER  NOT NULL
);
```

```sql
-- PostgreSQL: 同版本脚本
CREATE TABLE IF NOT EXISTS sandbox_whitelist_entries (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(32)   NOT NULL,
    value       VARCHAR(1024) NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_sandbox_whitelist_type_value
    ON sandbox_whitelist_entries (type, value);

CREATE TABLE IF NOT EXISTS sandbox_whitelist_seed (
    id          SMALLINT  PRIMARY KEY CHECK (id = 1),
    seeded_at   TIMESTAMP NOT NULL,
    entry_count INTEGER   NOT NULL
);
```

字段：

| 字段 | 语义 | 约束 |
| --- | --- | --- |
| `id` | 主键 | 自增；API 用它做路径参数 |
| `type` | `FILE_PATH` / `SHELL_COMMAND` / `HTTP_DOMAIN` | 非空；枚举字符串 |
| `value` | 白名单值（按类型规范化后） | 非空；≤1024；与 type 联合唯一 |
| `description` | 可选说明 | ≤255 |
| `created_at` / `updated_at` | 创建/更新时间 | `Instant`；SQLite 存 epoch millis，PG 存 TIMESTAMP |

`sandbox_whitelist_seed` 字段：

| 字段 | 语义 | 约束 |
| --- | --- | --- |
| `id` | 恒为 1 | 主键 + `CHECK (id = 1)`，物理上只允许一行 |
| `seeded_at` | 播种完成时刻 | 非空；诊断用，运维可据此判断"这份库是哪一天从 YAML 迁过来的" |
| `entry_count` | 本次播种写入的条目数 | 非空；**允许为 0**——空配置首次启动也写标记（见 §5.3） |

> **为什么不用通用 `sableos_meta(key, value)` 键值表**：两者实现成本相同，但通用元数据表是平台级概念（将来谁都能往里塞键），会把这个 feature 的评审面扩大到"要不要引入全局元数据表"。专用单行表爆炸半径局限在本 feature 内；将来确有第二个使用方时再抽通用表。

`SMTP_ENDPOINT` 暂不进入枚举（2026-10-27 已确认）：核心阶段没有 SMTP 动作，存了也不会被执行；等 `EmailNotifyAdapter` 落地时再加类型和校验，避免"存了但不生效"的误导。

### 5.2 实体与仓库

- 实体：`sableos-storage/src/main/java/com/sableos/storage/sandbox/SandboxWhitelistEntryEntity.java`，映射上表；`@PrePersist` 填 `createdAt`/`updatedAt`，`@PreUpdate` 刷新 `updatedAt`。日期映射对齐 `MemoryEntryEntity`。
- 播种标记实体：`SandboxWhitelistSeedEntity`（同包），映射 `sandbox_whitelist_seed`；无 setter 需求，构造即完整状态。
- 仓库：`SandboxWhitelistEntryRepository extends JpaRepository<SandboxWhitelistEntryEntity, Long>`，提供 `Optional<...> findByTypeAndValue(...)`、`List<...> findAllByOrderByTypeAscValueAsc()`；`SandboxWhitelistSeedRepository extends JpaRepository<SandboxWhitelistSeedEntity, Short>`，只需继承来的 `existsById(1)`。
- 存储实现：`JpaSandboxWhitelistStore implements SandboxWhitelistStore`，负责实体 ↔ core 记录映射；`seedIfUnseeded`、`update` 等写方法加 `@Transactional`（`sableos-storage` 已有 `spring-boot-starter-data-jpa` 带来的事务支持，`sableos-tool` 不需要新增 `spring-tx`）。

### 5.3 DB 为运行时事实源；首次启动播种一次（seed-once）

**已确认（2026-10-27）**：加 DB，白名单以数据库为运行时事实源；`application.yaml` 只在**首次启动**播种一次。播种后 DB 是唯一事实源，改 YAML 不会改变运行时白名单；要回到配置需清空条目表**和**播种标记，或走后续的显式重置接口。

```text
启动
  → 若 sandbox_whitelist_seed 无 id=1 行（"从未播种过"）：
        把 application.yaml 三项配置插入 sandbox_whitelist_entries
        同一事务内插入 sandbox_whitelist_seed(id=1, seeded_at=now, entry_count=N)
        （N 可以为 0：空配置也写标记）
  → 从 DB 重建 WhitelistPolicy → whitelistSandbox.refresh(...)
  → 若已有标记行：忽略 application.yaml 的三项白名单配置
```

**判据必须是 `sandbox_whitelist_seed` 有没有行，而不是条目表空不空。** 用条目表判空有一个必现缺陷：管理员通过 API 删光了所有条目后，条目表与"从未播种"状态完全同构，下次启动会把 `application.yaml` 再插一遍——正好推翻"API 删除的条目在重启后不会复活"这个核心承诺。而且当前 `application.yaml` 三项都是 `[]`，首次播种插入 0 行，条目表仍然为空，这个循环从第一天起就成立。

播种标记与条目写入在**同一事务**内完成：要么"条目 + 标记"都落库，要么都不落；不会出现"条目录进去了、标记没写成"导致下次启动重复播种。并发启动（同机多进程指向同一 SQLite）由标记行的主键兜底，后到者撞唯一约束后回查确认已播种，不再插入。`entry_count` 允许为 0 是刻意设计——空配置首次启动同样要落标记，否则"空配置"会被反复当成"未播种"。

理由：

- CRUD 的语义必须是"数据库说什么就是什么"，否则删除一条配置项后它又会被 YAML 复活；
- 避免每次启动都要合并两份来源、处理"配置删了但 DB 还在"的歧义；
- 首次播种让现有部署平滑迁移，不改 `application.yaml` 的默认值。

代价与边界：

- 首次启动后，改 `application.yaml` 不会改变运行时白名单；要用 API 或同时清空两张表。文档与启动日志必须写明这一点。
- 多实例部署时每个实例各自读同一张表，但快照刷新不跨实例；核心阶段单实例，扩展阶段再加轮询/共享缓存。
- 如果运维确实想"用配置重置"，后续可加显式 `POST /api/v1/sandbox/whitelist/reseed?confirm=true`（本期不做，避免误操作把手工条目清掉）。该接口的语义是"清空条目 + 重播 YAML + 刷新快照"，标记行不删只更新。

**备选（不采用）**：CRUD 写回 `sandbox.yaml`/`application.yaml`。并发写、注释保留、YAML 格式、原子替换都难做，且多实例下无法保证一致；与项目已有的 SQLite 注册表思路也不一致。

## 6. REST API 契约

### 6.1 端点

基础路径：`/api/v1/sandbox/whitelist`。

| 方法 | 路径 | 语义 | 成功状态 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/sandbox/whitelist?type=FILE_PATH` | 列表；`type` 可选，缺省返回全部；按 `type, value` 排序 | 200 |
| `GET` | `/api/v1/sandbox/whitelist/{id}` | 单条详情 | 200 |
| `POST` | `/api/v1/sandbox/whitelist` | 新增 `{type, value, description?}` | 201 |
| `PUT` | `/api/v1/sandbox/whitelist/{id}` | 更新 `value` / `description`；`type` 不可变 | 200 |
| `DELETE` | `/api/v1/sandbox/whitelist/{id}` | 删除 | 200（空 `data`） |

说明：

- 不使用 204：项目的响应信封恒为 5 字段，`DELETE` 用 200 + `ApiResponse.ok(null)` 保持形状一致。
- `type` 不做更新：改类型等于"删旧建新"，避免一条记录在两种规则间漂移；需要改类型就 `DELETE` + `POST`。
- 列表不分页：白名单是低基数配置（建议每类上限 1000 条，超出返回 400；上限可作为常量，不新增配置键）。
- Controller 只做参数校验、信封包装、异常映射；业务逻辑全部在 `SandboxWhitelistAdmin`。

### 6.2 请求与响应

新增：

```json
POST /api/v1/sandbox/whitelist
{
  "type": "FILE_PATH",
  "value": ".sableos/agents",
  "description": "所有 Agent 目录"
}
```

```json
201 Created
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 1,
    "type": "FILE_PATH",
    "value": ".sableos/agents",
    "description": "所有 Agent 目录",
    "createdAt": "2026-10-27T10:00:00Z",
    "updatedAt": "2026-10-27T10:00:00Z"
  },
  "timestamp": "2026-10-27T10:00:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

列表：

```json
GET /api/v1/sandbox/whitelist?type=HTTP_DOMAIN
{
  "code": 0,
  "message": "ok",
  "data": [
    {
      "id": 7,
      "type": "HTTP_DOMAIN",
      "value": "api.open-meteo.com",
      "description": "天气 Demo",
      "createdAt": "2026-10-27T10:00:00Z",
      "updatedAt": "2026-10-27T10:00:00Z"
    }
  ],
  "timestamp": "2026-10-27T10:00:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

错误沿用 `GlobalExceptionHandler` 的信封与状态码：

| 场景 | 状态码 | `message` 示例 |
| --- | --- | --- |
| `type` 缺失/非法、`value` 空白或超长、域名/命令格式非法 | 400 | `HTTP_DOMAIN 格式非法：只接受主机名或 *.主机名` |
| `id` 不存在 | 404 | `白名单条目不存在: 42` |
| `(type, value)` 已存在 | 409 | `白名单条目已存在: HTTP_DOMAIN api.open-meteo.com` |
| 内部错误 | 500 | `Internal server error`（不泄漏内部细节） |

> 400 的 message **只描述字段与违反的规则，不回显调用方传入的原值**（理由见 §6.5）。404 / 409 回显的是归一后的 `type`/`value` 与路径参数 `id`，这些值已通过校验或已存在库中，不构成注入面。

### 6.3 校验规则

三类值都遵循「**先归一、再查重、后入库**」：归一后的形态既是入库形态，也是唯一索引的判重形态，也是 API 返回给调用方的形态。归一必须是幂等的（`normalize(normalize(x)) == normalize(x)`）。

| 类型 | 校验规则 | 归一化 |
| --- | --- | --- |
| `FILE_PATH` | 非空、≤1024；`Path.of(value)` 不抛 `InvalidPathException`；不含 NUL/CR/LF；归一后不得为空 | `Path.of(value.trim()).normalize()` 后把分隔符统一成 `/`；保留相对/绝对形式，不转绝对路径 |
| `SHELL_COMMAND` | 非空、≤1024；单 token（不含空白）；允许绝对路径可执行文件；不含 CR/LF | `value.trim()`；大小写保持原样（匹配是精确、大小写敏感） |
| `HTTP_DOMAIN` | 非空、≤1024；只能是 host 或 `*.host`；不含 scheme/port/path/空白/`..`；通配只能在最左且必须带 `*.`；**只接受 ASCII**（非 ASCII 返回 400，提示改用 punycode） | `trim` + `toLowerCase(Locale.ROOT)` |

**为什么 `FILE_PATH` 必须归一**：`.sableos/agents`、`.sableos/agents/`、`.sableos//agents`、`.sableos/./agents`、`.sableos/a/../agents` 在 `Path.normalize()` 后是同一个根，运行时 `startsWith` 命中的也是同一个根。如果不归一，唯一索引 `(type, value)` 对它们全部放行，管理台上看着是 5 条、语义上只有 1 条；删除其中一条后语义毫无变化，运营方会以为删除失败。归一后这五种写法都落成 `.sableos/agents`，第二条起返回 409。

**归一的两个细节**（Windows 实测，JDK 21）：

- **分隔符统一成 `/`**：`Path.normalize().toString()` 在 Windows 上产出 `.sableos\agents`，直接入库会把宿主机的分隔符风格固化进数据，换到 macOS/Linux 就匹配不上（运行时 `Path.of(".sableos\\agents")` 在 POSIX 上是名为 `.sableos\agents` 的单个文件）。统一成 `/` 后，两端 `Path.of(...)` 都能正确解析回同一个路径，这正是 §7.1「存储可移植」要的形态。
- **盘符大小写不归一**：`Path.of("C:/foo").normalize()` 与 `Path.of("c:/foo").normalize()` 分别保留 `C:` 与 `c:`，仍是两条记录。盘符大小写在实际使用中几乎总是一致（由 IDE/资源管理器/CI 给出），而把它强制大写会在 POSIX 上错误改写合法文件名。因此**接受这一残留重复**，由 §6.3 的幂等归一覆盖 99% 场景，文档明示该边界。

**`HTTP_DOMAIN` 只接受 ASCII**，有三层理由：

1. **存了也用不上**。`URI.getHost()` 对非 ASCII 主机名返回 `null`（JDK 21 实测，全角点 `．` 同样返回 `null`），而 [WhitelistSandbox.java:119](file:///D:/idea/sableos/sableos-tool/src/main/java/com/sableos/tool/sandbox/WhitelistSandbox.java#L119) 在 host 为 null 时判「URL 缺少主机名」并拒绝。若 CRUD 接受 `中文.example.com` 并存成 punycode，运行时用 `https://中文.example.com/` 请求**仍会被拒**，且拒绝理由是「缺少主机名」——运营方看审计日志会误判为 Agent 拼错了 URL，而不是"这条白名单格式不对"。
2. **同形异义字造成假放行**。西里尔 `а`（U+0430）与拉丁 `a`（U+0061）视觉一致但编码不同。运维本想加 `google.com`，实际可能存进一个视觉一模一样的西里尔域名：它不会造成绕过（确实是另一个域名，请求仍被拒），但管理台上看着"已经放开了"——这是一种比直接拒绝更危险的**安全错觉**。ASCII-only 从源头消除这一类混淆。
3. **全角字符被静默改写**。实测 `ＡＰＩ.example.com` 经 `IDN.toASCII` 转出 `api.example.com`，全角→ASCII 的 NFKC 化让运营方以为加的是"全角版本"，实际加的是普通 ASCII 域名，语义与预期不符且无任何提示。

核心阶段没有中文域名使用场景，直接拒非 ASCII 最省事：存储形态、唯一索引、`getHost()` 结果三者完全同构，24 节冻结的 `checkHttpUrl` 一行都不用改。将来真要支持，再连同 `getRawAuthority()` 回退 + `IDN.toASCII` 归一一起做，那时是一处完整改动（会动 `checkHttpUrl`，需回归现有 29 例），而不是现在这种"存得进去但用不了"的半成品。跨文档口径见 `AGENTS.md` 第 10 章。

校验失败抛 `WhitelistValidationException`（**不继承 `IllegalArgumentException`**），由 `GlobalExceptionHandler` 新增的精确 handler 映射成 400，message 只描述「哪个字段、违反了哪条规则」，**不回显原始输入值**；理由与实现见 §6.5。

### 6.4 新增对外概念清单（软门禁核对用）

| 类别 | 新增项 |
| --- | --- |
| REST 路径 | `/api/v1/sandbox/whitelist`、`/api/v1/sandbox/whitelist/{id}` |
| 配置键 | `sableos.sandbox.admin.enabled`（默认 `true`，产品决策；可显式设 `false` 关闭） |
| 数据表 | `sandbox_whitelist_entries`、`sandbox_whitelist_seed`（Flyway V5，SQLite + PostgreSQL） |
| public 类型 | `WhitelistType`、`WhitelistEntry`、`WhitelistCommand`、`SandboxWhitelistAdmin`、`SandboxWhitelistStore`、`WhitelistValidationException`、`WhitelistEntryNotFoundException`、`WhitelistDuplicateException`、`SandboxWhitelistController`、`JpaSandboxWhitelistStore`、`SandboxWhitelistSeedEntity`、`SandboxWhitelistSeedRepository` |

### 6.5 领域异常与 400 映射

三个领域异常都**不继承 `IllegalArgumentException`**，各自新增一个精确 handler：

| 异常 | 状态码 | message 构造规则 |
| --- | --- | --- |
| `WhitelistValidationException` | 400 | 只含字段名 + 规则描述，如 `HTTP_DOMAIN 格式非法：只接受主机名或 *.主机名`；**不含原始输入** |
| `WhitelistEntryNotFoundException` | 404 | `白名单条目不存在: {id}` |
| `WhitelistDuplicateException` | 409 | `白名单条目已存在: {type} {归一后的 value}` |

**为什么不继承 `IllegalArgumentException`**：既有 [GlobalExceptionHandler.java:28](file:///D:/idea/sableos/sableos-web/src/main/java/com/sableos/web/GlobalExceptionHandler.java#L28) 的 400 handler 把 `ex.getMessage()` 原样写进响应体（只有日志过 `sanitize`）。继承它会带来两个问题：

1. **用户输入原样回显**。`value 不是合法的 HTTP_DOMAIN: http://example.com/x` 这种 message 会把调用方输入直接反射进响应体；管理台与 API 同源部署，若管理台用 `innerHTML` 渲染错误提示，就是一个真实的 XSS 面。这是决定性理由。
2. **异常归类边界不可控**。`InvalidPathException`、`NumberFormatException`、`URI.create` 的失败都继承自 `IllegalArgumentException`（已实测确认）。若本 feature 的领域异常也继承它，那么"我方校验主动拒绝"与"JDK 内部解析失败"在 handler 层无法区分，将来想给前者定制 message、给后者降级处理时就没有干净的切分点。注意这**不是**说现状会泄漏敏感信息——JDK 这些异常的消息通常不含敏感数据；问题在于归类模糊，而非既存泄漏。

独立异常 + 精确 handler 后，三者互不干扰：`WhitelistValidationException` 只由本 feature 的校验规则抛出，message 由我们自己逐条构造，天然不含用户输入；其余 `IllegalArgumentException` 仍走既有 400 handler，行为不变。

handler 仍放在 `sableos-web/GlobalExceptionHandler`，与既有 404/409 handler 并列——`WhitelistEntryNotFoundException` / `WhitelistDuplicateException` 与既有 `ResourceNotFoundException` / `ConflictException` 语义同构，但**不复用它们**：那是 web 模块的通用异常，本 feature 的异常定义在 core（`SandboxWhitelistAdmin` 契约层），web 只做映射，保持 `web → core` 单向依赖。

领域异常放 core 的依据：`SandboxWhitelistAdmin` 是 core 接口，其`add/update/delete` 的失败语义必须由 core 定义，否则 tool 侧的实现类得反向依赖 web 的异常类型。

### 6.6 审计日志

每次增删改记一条 INFO 日志，字段为 `traceId`、操作（`create` / `update` / `delete`）、`type`、`id`、净化后的 `value`。`value` 入日志前必须 `replaceAll("[\r\n]", "_")`（保持 AGENTS §5.3 的 findsecbugs 形态）——`value` 来自调用方，CR/LF 可用于伪造日志行。`description` 不进日志（可能被写入敏感说明）。

### 7. 跨平台兼容

### 7.1 文件路径

- **存储**：保存**归一后**的形态——`Path.of(value.trim()).normalize()` 再统一分隔符为 `/`，不保存 `toAbsolutePath()` 的结果。不存绝对路径的理由：同一份 SQLite 换机器/换目录后失效，也会把宿主机绝对路径暴露给 API 调用方。归一化与判重理由见 §6.3。
- **校验**：用本机 `Path.of(value)` 判断语法；`InvalidPathException` → 400。Windows 的 `C:\...`、UNC `\\server\share`，macOS/Linux 的 `/...`，以及混合分隔符 `C:/...` 都由 JDK 的 `Path` 解析。
- **运行时**：`WhitelistSandbox` 仍用 `Path.of(value).toAbsolutePath().normalize()` 后 `startsWith` 比较；相对路径按**进程当前工作目录（CWD）**解析，与第 24 节一致。注意 `WORKSPACE_ROOT` 只影响 datasource URL 与 `Workspace.root()` 的建目录行为，**不参与**沙箱的路径解析——改变工作区后要让白名单继续命中，须从新工作区根启动进程（CWD 随之改变），而不是只改 `WORKSPACE_ROOT`。
- **大小写**：Windows 文件系统通常不区分，Linux 区分，macOS 通常不区分但可配成区分；`Path.startsWith` 跟随文件系统提供者。归一不改写大小写，同一路径在不同 OS 上的命中行为仍可能不同；文档与 UI 建议使用 canonical 小写路径。
- **不解析符号链接**：`normalize()` 不解析 symlink；这是第 24 节已记录的"劝阻级"边界。若 Agent 的 `skills/` 软链接指向白名单外，字典序路径仍可能通过，严格隔离要靠扩展阶段的容器/microVM。

### 7.2 Shell 命令

- 白名单条目是**一个 token**，与 `WhitelistSandbox.checkShellCommand` 的首 token 精确、大小写敏感比对。
- 不做 OS 过滤：同一个 DB 里可以同时有 `python`、`python3`、`cmd`、`sh`；未命中的条目只是不生效。
- Windows 常见：`cmd`、`powershell`、`python`、`py`、`node`；`.exe` 后缀是**不同 token**（模型输出 `python.exe` 就需显式加 `python.exe`，同一二进制，不额外扩大能力）。
- macOS/Linux 常见：`sh`、`bash`、`python3`、`node`。
- 绝对路径可执行文件（如 `/usr/bin/python3`）允许；校验时若能识别出路径分隔符，用 `Path.of` 做语法校验。
- 解释器 = 宿主进程代码执行权限；API 不阻止添加，但日志 WARN 提示，管理台应有醒目提示。

### 7.3 HTTP 域名

- 只接受 host 或 `*.host`；不接受 `http://`、端口、路径、查询串、空白。
- **只接受 ASCII**：含非 ASCII 字符的域名返回 400，提示改用 punycode 形式（`xn--...`）提交。理由见 §6.3 —— `URI.getHost()` 对非 ASCII 主机名返回 `null`，存进去也用不上。
- 统一小写（`toLowerCase(Locale.ROOT)`）；入库与比较用同一形态。
- 通配只允许最左侧 `*.`，匹配时保留点号边界（第 24 节的 `matchesDomain` 已实现；CRUD 只做语法校验，不重复实现匹配逻辑）。
- 无 OS 差异。

### 7.4 兼容矩阵

| 维度 | Windows | macOS | Linux | 处理 |
| --- | --- | --- | --- | --- |
| 路径分隔符 | `\` / `/` | `/` | `/` | 接受本机语法；入库统一 `/`；种子 YAML 用 `/` |
| 盘符 / UNC | `C:\`、`\\server\share` | — | — | `Path.of` 解析；语法校验；盘符大小写不归一 |
| 路径大小写 | 通常不区分 | 通常不区分（可配） | 区分 | 归一不改写大小写；文档提示 canonical 小写 |
| 路径等价写法 | `//`、`./`、`a/../b`、末尾 `/` 归一到同一形态 | 同左 | 同左 | `normalize()` + 分隔符统一 |
| Shell 可执行名 | `cmd`、`powershell`、`python`、`py` | `sh`、`bash`、`python3` | 同 macOS | 不做 OS 过滤；示例分组 |
| `.exe` 后缀 | 是不同 token | — | — | 需要时显式添加 |
| 域名 / IDN | 只接受 ASCII | 同左 | 同左 | 非 ASCII 返回 400，提示用 punycode |
| 编码 / 换行 | JSON UTF-8；DB 文本 | 同左 | 同左 | 不写 OS 相关换行 |
| 并发写 SQLite | WAL + busy_timeout 已配 | 同左 | 同左 | CRUD 低频；单实例假设 |

## 8. 安全设计

白名单管理接口是**root 等价**能力：调用方可以加 `FILE_PATH=/`、`SHELL_COMMAND=python`，再让 Agent 执行任意代码。核心阶段 Web Service 本身没有认证（`TechnicalSolution.md` §7.5），因此：

1. **默认开启（产品决策）**：`application.yaml` 新增 `sableos.sandbox.admin.enabled: true`；Controller 用 `@ConditionalOnProperty` 条件装配，运维可显式设 `false` 关闭；关闭时访问该路径走既有 404 映射。
2. **启动即警告**：默认开启意味着启动时必须 `LOG.warn` 明确提示"白名单管理 API 已启用（root 等价），必须只绑回环或置于认证反向代理之后；关掉请设 `sableos.sandbox.admin.enabled=false`"。
3. **部署建议**：`server.address=127.0.0.1`；或由企业网关做认证/授权；不要把 `serve` 直接暴露到公网。默认开启不改变"管理 API = root 等价"的威胁判断，只是把可用性放在安全默认之前，风险由部署方承担。
4. **审计**：每次增删改记一条 INFO 日志（字段与净化规则见 §6.6）；`ApiResponse.traceId` 与日志可对账。是否落独立审计表放扩展阶段。
5. **不回显用户输入**：400 的 message 不含原始输入值；领域异常见 §6.5。
6. **输入校验**：长度、类型、格式、唯一约束；DB 唯一索引兜底并发重复；不信任客户端传入的 `id`。
7. **不记录秘密**：`description` 可能被运营方写入敏感说明，日志不记 `description` 全文。
8. **CSRF/限流/RBAC**：核心阶段不做；管理台与 API 同源部署时由网关负责，扩展阶段补。

## 9. 验收 harness

### 9.1 测试分层

| 层 | 测试类 | 守点 |
| --- | --- | --- |
| 规则单测 | `WhitelistRulesTest` | 三类值的校验与归一；**FILE_PATH 等价写法收敛**（`//`、`./`、`a/../b`、末尾 `/` 归一到同一条）；路径语法（POSIX / Windows 盘符 / UNC / 相对 / `..`）；**非 ASCII 域名被拒**；命令空白与大小写；域名格式、通配、lookalike 输入 |
| 服务单测 | `SandboxWhitelistAdminTest` | 增删改查；重复 → 冲突；不存在 → 404；更新后新值生效、旧值失效；删除后立即拒绝；**删光条目后重启不重播 YAML**；快照刷新 |
| 快照单测 | `WhitelistSandboxTest`（扩展现有 29 例） | `refresh` 后 `enforce` 立即看到新策略；空策略 deny-all；校验路径仍走旧规则 |
| 存储单测 | `SandboxWhitelistEntryRepositoryTest` / `SandboxWhitelistSeedRepositoryTest` | V5 迁移建两张表；唯一索引；`type + value` 查询；时间戳；标记表 `CHECK (id = 1)` 生效；`entry_count = 0` 可写入 |
| Controller 切片 | `SandboxWhitelistControllerTest`（`@WebMvcTest`） | 201/200/400/404/409；信封 5 字段；500 不含内部 message；**400 message 不含原始输入**；`type` 不可变 |
| 集成 | `SandboxWhitelistApiIT`（`@SpringBootTest` + 临时 SQLite + MockMvc） | CRUD 全链路；`ToolExecutor` 调 `read_file` 验证"加条目后放行、删条目后拒绝"；审计落库 |
| 跨 OS | CI 矩阵 | Ubuntu / Windows / macOS 跑同一组规则与服务测试 |

### 9.2 关键用例

```java
@Test
@DisplayName("新增文件路径条目后_read_file 立即放行")
void addFilePath_thenReadFileIsAllowed() throws IOException {
    Path dir = Files.createTempDirectory("whitelist-");
    Path file = Files.writeString(dir.resolve("note.txt"), "hello");
    WhitelistEntry entry = admin.add(
        new WhitelistCommand(WhitelistType.FILE_PATH, dir.toString(), "test"));

    assertThat(entry.id()).isNotNull();
    assertDoesNotThrow(() ->
        sandbox.enforce(new SandboxAction(ActionType.FILE_READ, file.toString())));

    admin.delete(entry.id());
    assertThrows(SandboxViolationException.class, () ->
        sandbox.enforce(new SandboxAction(ActionType.FILE_READ, file.toString())));
}
```

```java
@Test
@DisplayName("重复条目返回冲突_不是静默覆盖")
void duplicateEntry_isConflict() {
    admin.add(new WhitelistCommand(WhitelistType.HTTP_DOMAIN, "api.example.com", null));

    assertThrows(WhitelistDuplicateException.class, () ->
        admin.add(new WhitelistCommand(WhitelistType.HTTP_DOMAIN, "API.EXAMPLE.COM", null)));
}
```

```java
@Test
@DisplayName("Controller 500 不泄漏内部异常细节")
void internalError_doesNotLeakMessage() throws Exception {
    when(admin.list(null)).thenThrow(new IllegalStateException("jdbc:sqlite:/data/x.db"));

    mockMvc.perform(get("/api/v1/sandbox/whitelist"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.message").value("Internal server error"))
        .andExpect(content().string(not(containsString("jdbc:sqlite"))));
}
```

```java
@Test
@DisplayName("400 不回显调用方输入的原始值")
void validationError_doesNotEchoInput() throws Exception {
    String payload = "<img src=x onerror=alert(1)>";

    mockMvc.perform(post("/api/v1/sandbox/whitelist")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"HTTP_DOMAIN\",\"value\":\"" + payload + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString("onerror"))));
}
```

```java
@Test
@DisplayName("删光条目后重启_不重新播种_YAML 不复活已删除条目")
void deletedAllEntries_restart_doesNotReseed() {
    admin.add(new WhitelistCommand(WhitelistType.SHELL_COMMAND, "python", null));
    for (WhitelistEntry e : admin.list(null)) {
        admin.delete(e.id());
    }

    // 模拟重启：新建上下文，YAML 里仍有 shell.allowed_commands=[python]
    try (var ctx = new AnnotationConfigApplicationContext(SeedTestConfig.class)) {
        assertThat(ctx.getBean(SandboxWhitelistAdmin.class).list(null)).isEmpty();
    }
}
```

### 9.3 跨 OS CI

`.github/workflows/ci.yml` 目前只有 Ubuntu。新增一个聚焦 job：

```yaml
sandbox-compat:
  strategy:
    matrix:
      os: [ubuntu-latest, windows-latest, macos-latest]
  runs-on: ${{ matrix.os }}
  steps:
    - uses: actions/checkout@v5
    - uses: actions/setup-java@v6
      with:
        distribution: temurin
        java-version: '21'
        cache: maven
    - run: >
        mvn -B -pl sableos-core,sableos-storage,sableos-tool,sableos-web,sableos-boot -am test
        -Dtest='WhitelistRulesTest,SandboxWhitelistAdminTest,SandboxWhitelistControllerTest,WhitelistSandboxTest'
        -Dsurefire.failIfNoSpecifiedTests=false
```

> action 版本与仓库现有 [ci.yml](file:///D:/idea/sableos/.github/workflows/ci.yml) 保持一致（`checkout@v5` / `setup-java@v6`），勿写入旧版本导致两套并存。

约束：

- 测试目录一律用 `@TempDir`，不硬编码 `/tmp` 或 `C:\temp`；
- 断言路径用 `Path` 对象/`Path.toString()`，不手拼分隔符；
- **归一断言写成与 OS 无关的形式**：把结果再 `Path.of(result)` 后比较，不要断言字符串里出现 `\` 或 `/`——入库形态固定为 `/`，但它在本机 `Path.of` 解析后的字符串表示因 OS 而异；
- Shell 真实进程测试只在 `ShellToolsTest` 里按 `os.name` 分支，本设计的规则测试不启动进程；
- 域名断言与 OS 无关；
- 全量 `mvn clean verify` 仍在 Ubuntu 门禁跑；矩阵只跑聚焦测试，避免 macOS runner 过慢。

### 9.4 `mvn clean verify` 与人工项

自动门禁：

```bash
mvn spotless:apply
mvn clean verify
```

人工项：

1. 默认配置下（`sableos.sandbox.admin.enabled=true`）在管理台或 curl 加一条 `FILE_PATH`，让 Agent `read_file` 成功；删除后同一动作失败。再设 `false` 重启，确认该路径返回 404。
2. 在 Windows / macOS / Linux 各跑一次 CRUD，确认路径语法与命令条目行为符合本机预期；重点验证 `.sableos/agents/`、`.sableos//agents` 这类等价写法在第二次提交时返回 409。
3. 重启进程，确认条目仍在、快照从 DB 恢复。
4. **删光所有条目后重启**，确认 `application.yaml` 里的条目没有被重新播回来（验证 §5.3 的标记表判据）。
5. 用一条危险条目（`FILE_PATH=/` + `SHELL_COMMAND=python`）验证"管理 API = root 等价"的警告确实生效（仅在隔离环境做）。

## 10. 实施拆解

| 阶段 | 任务 | 依赖 |
| --- | --- | --- |
| 0 | core：`WhitelistType` / `WhitelistEntry` / `WhitelistCommand` / `SandboxWhitelistAdmin` / `SandboxWhitelistStore`（含 `seedIfUnseeded`）/ 三个领域异常 | 无 |
| 1 | storage：V5 双轨迁移（**两张表**）、两个实体、两个仓库、`JpaSandboxWhitelistStore` | 0 |
| 2 | tool：`WhitelistRules`（校验 + 归一）、`WhitelistPolicy`、`WhitelistSandbox.refresh`、`DefaultSandboxWhitelistAdmin` | 0、1 |
| 3 | web：`SandboxWhitelistController`、**三个精确异常 handler**、`sableos.sandbox.admin.enabled` 条件装配 | 0 |
| 4 | boot：装配、播种 `ApplicationRunner`（seed-once，**判据看标记表**）、启动 WARN | 1、2、3 |
| 5 | harness：规则/服务/快照/存储/Controller/集成测试 + CI 矩阵 | 0~4 |

每个阶段仍然"测试先于或伴随实现"：阶段 0 先写规则与异常测试，阶段 1 先写迁移/仓库测试（含标记表 `CHECK` 约束与 `entry_count = 0`），阶段 2 先写归一与刷新单测，阶段 3 先写 `@WebMvcTest`（含 400 不回显），阶段 4 写集成测试（含"删光条目后重启不重播"）。

**第三方依赖**：不新增。`sableos-storage` 已有 JPA/事务；`sableos-tool` 通过 core 接口访问存储；`sableos-web` 通过 core 接口访问管理服务。

## 11. 明确不做

- 不做 SMTP 端点类型（等 `EmailNotifyAdapter`）；
- 不做多实例快照同步；
- 不做认证 / RBAC / 限流 / CSRF（核心阶段 Web Service 无认证；管理 API 默认开启，靠回环/网关与启动 WARN 兜底）；
- 不做批量替换 / 导入 / 导出（后续可加 `bulk`）；
- 不做 YAML 写回；
- 不做独立白名单审计表（本期只写日志 + traceId）；
- 不做容器 / microVM 隔离；
- 不做运行时热更新 `application.yaml`（已确认 DB 为运行时事实源；配置只在首次启动播种一次）；
- **不做 IDN / 非 ASCII 域名支持**（只接受 ASCII，非 ASCII 返回 400，理由见 §6.3）；
- **不做 FILE_PATH 的盘符大小写归一**（`C:` / `c:` 视为两条，理由见 §6.3）。

## 12. 决策记录与待决策

### 12.1 已确认（2026-10-27）

| # | 决策 | 结论 |
| --- | --- | --- |
| 1 | 管理接口默认开还是关 | **默认开**；配套启动 WARN + 回环/网关建议 |
| 2 | 配置播种时机 | **首次启动播种一次（seed-once）**；之后 DB 为唯一事实源，改 YAML 不生效 |
| 3 | 是否现在加 `SMTP_ENDPOINT` 类型 | **暂不加**，等邮件适配器 |
| 4 | 文件路径按 CWD 还是工作区根解析 | 沿用第 24 节 **CWD**；`WORKSPACE_ROOT` 不参与沙箱路径解析 |
| 5 | 白名单持久化方案 | **加 DB**：`sandbox_whitelist_entries` + `sandbox_whitelist_seed`（SQLite + JPA + Flyway V5）；DB 为运行时事实源 |
| 6 | 播种判据（2026-10-29 补） | **看 `sandbox_whitelist_seed` 有无行**，不看条目表空否；否则删光条目后重启会重播 YAML |
| 7 | FILE_PATH 是否归一（2026-10-29 补） | **归一**：`normalize()` + 分隔符统一 `/`，令唯一索引真正生效；盘符大小写不归一 |
| 8 | 是否支持 IDN 域名（2026-10-29 补） | **不支持**：只接受 ASCII，非 ASCII 返回 400；理由见 §6.3 |
| 9 | 领域异常是否继承 `IllegalArgumentException`（2026-10-29 补） | **不继承**：独立异常 + 精确 handler，避免 400 回显用户输入，见 §6.5 |

### 12.2 未决项

无。所有影响实现的决策已确认，可按 §10 拆 feature 实施。

> 参考实现 oryxos 采用"每次启动把配置项幂等 `add` 进库"（配置 ∪ DB）；本设计明确选择 seed-once：API 删除的条目在重启后不会复活，代价是之后改 `application.yaml` 不生效。

### 12.3 记录但不阻塞

| # | 问题 | 本期结论 | 备注 |
| --- | --- | --- | --- |
| 10 | 是否加白名单审计表 | 本期只记日志（§6.6） | 扩展阶段再加表 |
| 11 | 是否支持批量导入 | 本期不做 | 管理台逐条编辑 |
| 12 | 多实例刷新 | 单实例假设 | 多实例用共享 DB + 定时刷新，扩展阶段 |

全部决策已确认（加 DB、默认开、SMTP 暂缓、CWD 路径、seed-once、播种标记表、FILE_PATH 归一、只收 ASCII 域名、领域异常独立），可按第 10 节拆 feature 实施；实施时仍走第 24 节同一套门禁：`mvn clean verify` 全绿、跨 OS 矩阵全绿、不自动 commit / push。

## 13. 参考实现：oryxos

> 源码位置：`D:\idea\oryxos`。关键文件：`oryxos-core/.../sandbox/SandboxWhitelist.java`、`SandboxWhitelistStore.java`、`oryxos-storage/.../JpaSandboxWhitelistStore.java`、`SandboxWhitelistRow.java`、`oryxos-tool/.../WhitelistSandbox.java`、`oryxos-web/.../SandboxWhitelistController.java`、`oryxos-storage/src/main/resources/db/migration/{sqlite,postgresql}/V1__baseline.sql`。

### 13.1 它做了什么

- **加了 DB**：`sandbox_whitelist` 表（`id`、`category`、`entry_value`、`created_at`，`UNIQUE(category, entry_value)`），随基线迁移建表；JPA 实体 `SandboxWhitelistRow` + 仓库 + `JpaSandboxWhitelistStore`。
- **运行时同实例双接口**：`WhitelistSandbox implements Sandbox, SandboxWhitelist`；内存用 `CopyOnWriteArrayList` / `ConcurrentHashMap.newKeySet()`；`add/remove` 写穿到 store，构造时从 store 恢复。
- **启动播种**：装配层对配置文件条目逐条 `whitelist.add(...)`（幂等、落库），另加工作区根；因此每次启动都是"配置 ∪ 数据库"。
- **API**：`GET /api/v1/sandbox/whitelist` 返回四类全量；`POST /api/v1/sandbox/whitelist/{category}` body `{"value": ...}`（幂等，返回 `changed`）；`DELETE /api/v1/sandbox/whitelist/{category}?value=...`。没有 PUT / ID；"改" = 删 + 加。
- **默认值**：`application.yml` 非空：`.oryxos`、`ls/cat/echo/grep`、大量 Provider / IM / 天气域名；`SandboxDefaultsConfigTest` 锁死默认 shell 不含解释器。
- **FILE 存储**：入内存与入库都是归一后的绝对路径（`RealPathBoundary.project`），DB 值与本机绑定。
- **安全**：无认证、无开关，靠"假设内网 + 网络层隔离"；每次改动落 INFO 日志。

### 13.2 与本文设计的异同

| 维度 | oryxos | 本文设计 | 结论 |
| --- | --- | --- | --- |
| 持久化 | SQLite 表 + JPA store | 同左 | 采纳（已确认） |
| 分层 | core 契约 / storage JPA / tool 实现 / web Controller | 同左 | 采纳 |
| 运行时刷新 | 并发集合原地增删 | `AtomicReference<WhitelistPolicy>` 整体换快照 | 采用快照：更易保证一次 `enforce` 内读到一致策略 |
| API 形态 | category 路径 + value，无 ID / PUT | 统一资源 + ID + PUT | 采用 ID + PUT（已定） |
| 重复新增 | 幂等，`changed=false` | 409 | **采用 409**：显式失败比静默幂等更易被管理台正确反馈（已定） |
| 分类 | FILE / SHELL / HTTP / SMTP | 三类（SMTP 暂缓） | 保持本文 |
| 播种 | 每次启动配置 ∪ DB；删配置来源条目会复活 | seed-once + 播种标记表 | 保持本文（见 §5.3） |
| 播种判据 | 无标记，靠幂等 add | 独立标记表 `sandbox_whitelist_seed` | 保持本文：删光条目不重播 |
| FILE 存储 | 归一后的**绝对**路径 | 归一后的**相对/绝对原形式**，分隔符统一 `/` | 保持本文：可移植、不泄漏宿主机绝对路径 |
| 域名 | 未特别处理 IDN | 只接受 ASCII | 保持本文（见 §6.3） |
| 默认值 | 非空，含 Unix 命令 | 主配置空（`application.yaml` 三项均为 `[]`） | 保持本文（课程要求 deny-all 默认） |
| 管理接口开关 | 无，始终开启 | `sableos.sandbox.admin.enabled` 默认 true | 保留开关便于应急关闭 |
| 跨平台 | 未见 CI 矩阵；默认 shell 是 Unix 命令 | 三平台 CI 矩阵 + OS 分组示例 | 保持本文 |

### 13.3 可直接借鉴的点

1. **core 契约 + storage JPA + tool 实现 + web Controller 的分层**与本文一致，说明依赖倒置方向可行。
2. **写穿 + 启动恢复**的流程简单可靠：`add/remove` 同步落库，构造时 `loadAll` 恢复。
3. **`SandboxDefaultsConfigTest`** 用 `ApplicationContextRunner` 锁死默认 shell 不含解释器，这个 harness 模式可以直接用于本文的"默认值"回归。
4. **Controller 测试拆两层**：纯控制器委派测试 + standalone MockMvc 路由/400 测试；本文可沿用，再加 `@SpringBootTest` 集成测试覆盖真实 JPA。

> oryxos 的「幂等新增」不采纳：本文需要区分"新增成功"与"已存在"，否则管理台无法提示运营方"这条已经在列表里了"；409 由 DB 唯一索引兜底，不额外加查询。

### 13.4 不建议照搬的点

1. oryxos 的默认 shell 是 Unix 命令（`ls/cat/echo/grep`），Windows 上不可用；SableOS 要跨平台，默认 shell 应保持空，示例按 OS 分组。
2. oryxos 的 FILE 白名单入库为**绝对**路径，DB 与本机绑定、换机器即失效；SableOS 存归一后的原形式（相对/绝对），仅统一分隔符为 `/`，保持可移植。
3. oryxos API 没有 PUT，修改要"删 + 加"，管理台需要两步且中间态可能短暂失效；SableOS 保留 PUT。
4. oryxos 管理端点无开关、始终暴露；SableOS 保留 `sableos.sandbox.admin.enabled` 开关，便于应急关闭。
5. oryxos 没有播种标记，靠"每次启动幂等 add"回避删光后重播的问题；SableOS 用标记表，同时保住了"删掉不会复活"的语义。
