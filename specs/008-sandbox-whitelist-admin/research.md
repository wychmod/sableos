# Phase 0 Research: Sandbox 白名单管理接口

**Feature**: `008-sandbox-whitelist-admin` | **Date**: 2026-10-29

## 背景

设计稿 `docs/requirements/Sandbox白名单管理接口设计.md` 已完成方案定型（含 2026-10-27 与 2026-10-29 两轮决策）。本文只记录**实现层面的技术决策与实证依据**，不重复设计稿的需求叙述。

---

## D-01 幂等归一的实现方式

**决策**：`FILE_PATH` 用 `Path.of(value.trim()).normalize()` 后把分隔符统一替换为 `/`；盘符大小写**不**归一。

**实测依据**（Windows, JDK 21.0.3）：

```text
.sableos/agents      → .sableos\agents
.sableos/agents/     → .sableos\agents
.sableos//agents     → .sableos\agents
.sableos/./agents    → .sableos\agents
.sableos/a/../agents → .sableos\agents
C:/foo               → C:\foo        （盘符保持大写）
c:/foo               → c:\foo        （盘符保持小写）
```

**为什么必须统一分隔符**：`Path.normalize().toString()` 在 Windows 上产出反斜杠。若原样入库，同一份 SQLite 换到 POSIX 后 `Path.of(".sableos\\agents")` 会解析成名为 `.sableos\agents` 的单个文件（反斜杠在 POSIX 是合法文件名字符），白名单静默失效。

**为什么盘符大小写不归一**：强制大写会在 POSIX 上错误改写合法文件名（`/tmp/c:/foo` 是真实存在的路径形态，虽罕见）。实际使用中盘符大小写几乎总是一致（由 IDE / 资源管理器 / CI 给出），接受这一残留重复，在文档明示边界。

**幂等性验证**：`normalize(normalize(x)) == normalize(x)`，因 `Path.normalize()` 本身幂等，分隔符替换也幂等。

**弃用方案**：`toRealPath()`（解析符号链接 + 要求路径已存在，且返回绝对路径，破坏可移植性）；`toAbsolutePath()`（绑定本机目录结构）。

---

## D-02 唯一约束与并发重复的兜底

**决策**：DB 层 `UNIQUE(type, value)` 唯一索引作为最终防线；服务层先查重给出友好错误，捕获 DB 约束异常再兜底转 409。

**理由**：服务层查重存在 TOCTOU 窗口（两请求同时通过检查）。SQLite 与 PostgreSQL 都保证唯一索引原子性。捕获 `DataIntegrityViolationException` 后**必须回查确认**是唯一约束冲突而非其他完整性问题，否则会把外键失败之类误报成 409。

**注意**：SQLite 在并发写时可能先抛 `SQLITE_BUSY`（`busy_timeout=5000` 已配），该异常应上抛为 500 而非 409——它是资源竞争，不是数据冲突。

---

## D-03 快照发布的可见性

**决策**：`WhitelistSandbox` 持有 `AtomicReference<WhitelistPolicy>`，`WhitelistPolicy` 内部所有集合通过 `List.copyOf` / `Set.copyOf` 做不可变拷贝。

**理由**：
- `AtomicReference.set` 是 volatile 写，配合不可变对象即获得安全发布（JMM 保证）。
- 单次 `enforce` 只调用一次 `policy.get()`，局部变量持有引用，保证一次校验内看到一致策略（不会"读到一半被换掉"）。
- 不可变集合杜绝了调用方通过 `refresh` 传入的集合被外部后续修改的可能。

**为什么不用 `volatile` 字段直接赋 `WhitelistPolicy`**：`AtomicReference` 表达意图更明确，且便于后续若需 `compareAndSet`（乐观并发）时无需改字段类型。

**为什么不用 `synchronized` / `ReentrantLock`**：AGENTS §5.2 明确 JDK 21 下 `synchronized` 内阻塞调用会钉住虚拟线程；校验路径是每工具调用热路径，必须零锁。

---

## D-04 首次导入的严格模式与事务边界

**决策**：首次导入（配置 → DB）在**单一事务**内完成：插入全部条目 + 插入标记行。任一配置值归一化失败 → 抛异常 → 事务回滚 → 应用启动失败。

**理由**（FR-019 / FR-004）：
- 严格模式（而非跳过脏配置）与项目「异常不吞」口径一致，避免"存了但不生效"。
- 单事务保证不会出现「条目写了一半 + 标记没写」导致下次启动重复导入。
- 启动失败比静默降级更安全：白名单是安全边界，配置错误应立刻暴露。

**标记表判据**（FR-004）：`sandbox_whitelist_seed` 有 `id=1` 行即为"已导入"。不用条目表判空——因为「导入过 0 条」与「从未导入」在条目表上同构，会导致删光条目后重启重播。

**并发启动**：标记行主键 + `CHECK (id = 1)` 保证只有一行；后到者插入撞主键，捕获后回查确认已导入，不再插入。

---

## D-05 领域异常分层

**决策**：`WhitelistValidationException` / `WhitelistEntryNotFoundException` / `WhitelistDuplicateException` 均**不继承** `IllegalArgumentException`，三个精确 handler 在 `GlobalExceptionHandler`。

**实测依据**：`InvalidPathException`、`NumberFormatException`、`URI.create` 抛出的 `IllegalArgumentException` 均继承自 `IllegalArgumentException`（JDK 21 实测确认）。

**理由**：
1. **决定性**：既有 400 handler 把 `ex.getMessage()` 原样写入响应体（[GlobalExceptionHandler.java:28](../../sableos-web/src/main/java/com/sableos/web/GlobalExceptionHandler.java#L28)）。若继承，校验消息会回显用户输入；管理台与 API 同源部署时是 XSS 面。
2. **次要**：异常归类边界——若继承，"我方主动校验拒绝"与"JDK 内部解析失败"在 handler 层无法区分，将来无法给前者定制 message。

**异常定义位置**：core（`SandboxWhitelistAdmin` 契约层），因为 `add/update/delete` 的失败语义必须由契约定义，否则 tool 侧实现需反向依赖 web 的异常类型，破坏 `web → core` 单向依赖。

---

## D-06 校验规则的放置

**决策**：归一与校验逻辑放 `sableos-tool/WhitelistRules`（与 `WhitelistSandbox` 同包），不放 core。

**理由**：校验规则依赖"运行时如何解释值"——`WhitelistSandbox.checkFilePath` 用 `Path.of(...).normalize()`，`checkShellCommand` 用首 token 精确比对，`checkHttpUrl` 用 `matchesDomain` 通配匹配。归一规则必须与匹配规则同源同包，否则两边漂移会导致"存进去的形态和匹配时用的形态不一致"。

**core 只放数据类型与契约**，不放规则实现——core 不依赖 `java.nio.file` 之外的平台细节，也不该知道"域名通配要保留点号边界"这类实现知识。

---

## D-07 存储契约的最小面

**决策**：`SandboxWhitelistStore` 只暴露管理服务需要的操作，不暴露 ORM 概念。

```java
List<WhitelistEntry> findAll(WhitelistType type);   // type == null 表示全部
Optional<WhitelistEntry> findById(long id);
Optional<WhitelistEntry> findByTypeAndValue(WhitelistType type, String value);
WhitelistEntry insert(WhitelistCommand command, String normalizedValue);
WhitelistEntry update(long id, String normalizedValue, String description);
void delete(long id);
boolean isSeeded();
void markSeeded(int entryCount);
long countByType(WhitelistType type);
```

**理由**：`tool` 模块通过此接口访问存储，不依赖 `sableos-storage`（满足 ArchUnit 单向依赖）。归一的 `String normalizedValue` 由调用方（tool 层的 `DefaultSandboxWhitelistAdmin`）传入，存储层不重复实现校验——避免规则两处维护。

---

## D-08 配置键命名

**决策**：维持 `sableos.sandbox.admin.enabled`（应用级命名空间），不动根级 `sandbox.tool_result_max_chars`。

**理由**：与既有 `sableos.observability.*`、`sableos.providers` 同族，表示"应用级配置"。根级 `sandbox.tool_result_max_chars` 是早期遗留（且当前**无任何 Java 代码绑定该键**，见 grep 结果），不在本 feature 内调整，避免影响既有部署。需在文档注明两者区别。

**验证**：`grep -rn "tool_result_max_chars" --include=*.java` 无结果 → 该键当前未绑定，改它没有收益也无风险，但属范围外。

---

## D-09 装配位置（与设计稿的偏差）

**决策**：首次导入 `ApplicationRunner` 与启动告警落在 `sableos-cli`，不落 `sableos-boot`。

**理由**：实际装配根自第 18 节起是 `sableos-cli/SableOsRuntime`（唯一声明 `@EnableJpaRepositories` + `@EntityScan` 的地方）。`sableos-boot/SableOsApplication` 的 javadoc 已写明"18 节起，真正的装配根移到 cli"，且 fat JAR 的 `mainClass` 指向 `SableOsCli`。若把 Runner 放 boot，JPA 仓库在该上下文不可用。

**记录**：此偏差需在验收报告与 `tasks.md` 中显式标注，避免后续读取设计稿的人误以为在 boot。

---

## D-10 测试基座

**决策**：storage 层测试复用 `StorageTestApplication` 模式（`@SpringBootTest` + `@DynamicPropertySource` 临时 SQLite）；tool 层规则测试为纯单测（不启动 Spring）；web 层用 `@WebMvcTest`；集成测试需在 cli 或 boot 模块（有完整装配根）。

**理由**：`sableos-tool` 的 pom 无 `spring-boot-starter-test`（依赖来自 parent 的 `dependencyManagement`，但 `sableos-tool` 的 test scope 依赖需显式确认）。若缺失，规则测试用纯 JUnit + AssertJ 即可，`WhitelistSandboxTest` 现有的 29 例就是这种形态。

**集成测试落点**：`@SpringBootTest` 需要完整 JPA 上下文；`sableos-cli` 有 `SableOsRuntime` 但它是 `@SpringBootApplication`，直接用它做测试上下文会启动 CLI 全套。更稳妥的做法是在 `sableos-boot` 建测试引导类，显式 `@EnableJpaRepositories` + `@EntityScan` + `@ComponentScan`，仅在测试中启用所需 Bean。
