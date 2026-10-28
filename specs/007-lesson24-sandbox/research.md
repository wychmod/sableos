# Phase 0 Research: Sandbox 白名单校验

**Feature**: `007-lesson24-sandbox` | **Date**: 2026-10-27

## Decisions

### D-01 三键口径（file / shell / http），SMTP 留扩展阶段

- **Decision**: `WhitelistSandbox` 只读取 `file.allowed_paths`、`shell.allowed_commands`、`http.allowed_domains`，只实现路径、命令、域名三类私有校验；不实现 `checkSmtpEndpoint`。
- **Rationale**: 第 24 节课件明确"三类校验、三块配置"（第 18/130/301 行），第 23 节课件同样只列"文件、命令、域名"（第 161/186 行）；`ActionType` 四值中没有 SMTP 动作，`EmailNotifyAdapter` 本身属扩展阶段（技术方案 §6.8）。技术方案 §6.7 多出的 `smtp.allowed_endpoints` / `checkSmtpEndpoint` 在核心阶段不可达。
- **Alternatives considered**: ①新增 `SMTP_SEND` 动作类型——违反第 20 节"四值定死"的前序契约；②写一个永不被调用的私有方法——P3C/SpotBugs 会报不可达/未使用，静态门禁失败；③把 SMTP 校验挂到 `HTTP_REQUEST`——语义错误且会误拦普通 HTTP。均不采用。
- **Drift record**: `AGENTS.md` §10 已登记该偏差，待扩展阶段 `EmailNotifyAdapter` 落地时一并补齐。

### D-02 `WhitelistSandbox` 用 `@Component`，属性在 boot 侧启用

- **Decision**: `WhitelistSandbox` 标注 `@Component`，构造注入三个 `@ConfigurationProperties` 记录；在 `sableos-boot` 的 `ToolConfiguration` 上添加 `@EnableConfigurationProperties({FileSandboxProperties.class, ShellSandboxProperties.class, HttpSandboxProperties.class})`，删除原 `Sandbox` 的 `@Bean` 方法。
- **Rationale**: 课件的实现片段就是 `@Component`；组件扫描根为 `com.sableos`（`SableOsRuntime` / `SableOsApplication`），可覆盖 `sableos-tool`。属性记录需要显式启用，沿用仓库既有 `ProviderConfiguration` 的 `@EnableConfigurationProperties` 先例。
- **Alternatives considered**: 在 `ToolConfiguration` 手工 `new WhitelistSandbox(...)`——可行，但与课件片段不一致，且 `@ConfigurationProperties` 记录仍需启用；不采用。保留 `@Bean Sandbox` 并返回白名单实现——会与 `@Component` 形成两个同类型 bean；若删 `@Component` 则又要手工构造，偏离课件。

### D-03 `PermissiveSandbox` 移入测试源码

- **Decision**: 删除 `sableos-tool/src/main/java/com/sableos/tool/sandbox/PermissiveSandbox.java`，在 `sableos-tool/src/test/java/com/sableos/tool/sandbox/PermissiveSandbox.java` 以同包同全限定名重建。
- **Rationale**: 生产代码只保留唯一白名单实现（课件"写唯一的实现"）；前序 5 个测试类以该类型作放行替身，同 FQN 迁移可让它们零改动。测试作用域经 `spring-boot-starter-test` 携带 slf4j，原类的 WARN 日志可原样保留。
- **Alternatives considered**: ①保留在主源码但不装配——生产 jar 仍带全放行实现，存在误装配风险；②彻底删除并改 5 个前序测试——改动面更大且无额外收益。均不采用。

### D-04 路径校验：目标与白名单根都绝对化后按路径段比较

- **Decision**: `checkFilePath` 将目标 `Path.of(raw).toAbsolutePath().normalize()`；构造器把每个白名单根同样 `Path.of(root).toAbsolutePath().normalize()`，再用 `target.startsWith(root)` 判断。空/空白目标直接拒绝；空白根条目跳过。
- **Rationale**: 只对目标绝对化、根保持相对（课件片段的字面写法）会导致 `.sableos/agents/...` 这类相对根永远匹配失败；第 31 节课件明确使用相对根。`normalize()` 消除 `..`，`startsWith` 按路径段比较，`/work-evil` 不会被 `/work` 误命中。
- **Alternatives considered**: 字符串 `startsWith`——会被前缀相似目录绕过；`toRealPath()` 解析软链接——写操作目标通常不存在，且课件未要求解析软链接（应用层白名单定位为劝阻级）；均不采用。

### D-05 Shell 校验：首 token、去前导空白、精确大小写敏感

- **Decision**: `checkShellCommand` 对命令串 `strip()` 后按 `\s+` 取首 token，与 `Set<String>` 白名单精确比对；空/空白命令直接拒绝。
- **Rationale**: 课件明确"只取命令的第一个 token 比对"；技术方案 §6.7 说"精确比对可执行文件白名单"。当前 `ShellTools` 只传可执行文件名，首 token 逻辑对现状等价，也为未来传入完整命令串留出定义。
- **Alternatives considered**: 大小写不敏感匹配——Windows 上更宽松，但技术方案要求精确，且大小写变体会扩大放行面；不做。

### D-06 域名校验：URI 解析 + host 小写 + 通配点号边界

- **Decision**: `checkHttpUrl` 用 `URI.create` 解析；要求 scheme 为 `http`/`https`、host 非空；host 与白名单模式统一转小写；`*.example.com` 转为 `.example.com` 做 `endsWith`，精确模式做 `equals`。解析失败、缺 host、非 HTTP(S) 一律抛 `SandboxViolationException`。
- **Rationale**: 课件点名 `endsWith` 的经典漏洞——`evil-example.com`.endsWith(`example.com`) 为真，必须保留点号边界。主机名大小写不敏感，统一小写避免配置与 URL 大小写差异造成误拒。非 HTTP(S) 或不可解析的 URL 不应被当作可放行动作。
- **Alternatives considered**: 正则匹配——更复杂且容易漏转义；`java.net.URL`——已过时且行为不如 `URI` 明确；不做。

### D-07 空/缺失配置即 deny-all

- **Decision**: 构造器对三个列表做 null 安全处理（null → 空集合），随后空集合自然导致任何目标都不命中，即全部拒绝。`application.yaml` 中三个键已存在且注释写明"空列表 = 该类全部拒绝"。
- **Rationale**: 规格 FR-010 与课件"配置边界写进文档"要求；Spring Boot 对缺失集合的绑定行为不应成为安全默认值的依赖点。
- **Alternatives considered**: 缺失时视为"不校验"——直接违反安全默认值；不做。

### D-08 拒绝原因包含目标，但不新增日志

- **Decision**: `SandboxViolationException` 消息形如"路径不在白名单内: <path>"、"命令不在白名单内: <token>"、"域名不在白名单内: <host>"；不新增日志语句。
- **Rationale**: 异常经 `ToolExecutor` 收敛为 `ToolResult.failure` 并写入 `tool_invocations.error_message`，模型下一轮可读；不新增日志即不引入用户可控字符串的日志注入面（AGENTS §5.2）。
- **Alternatives considered**: 在沙箱内 `LOG.warn`——重复留痕且需 CRLF 净化；不做。

### D-09 失败路径完全复用既有审计

- **Decision**: 不修改 `ToolExecutor` 的逻辑，只把第 51-53 行过期的"24 节接线"TODO 注释改为说明"校验在工具入口完成"。
- **Rationale**: 课件"不改 `ToolExecutor` 的审计逻辑"；四个工具已分别调用 `enforce`，异常由既有 `catch (RuntimeException)` 收敛为 `success=false`。
- **Alternatives considered**: 把 `enforce` 上移到 `ToolExecutor`——需要从工具参数中推断动作目标，且会重复调用/改变既有接线；不做。

### D-10 测试用真实白名单实现 + Mockito 顺序断言

- **Decision**: `WhitelistSandboxTest` 直接 `new WhitelistSandbox(...)`；`NotifyToolsTest` 用 Mockito mock `Sandbox` / `NotifyChannelAdapter` 做 `InOrder` 断言，并另加一条用真实 `WhitelistSandbox`（空域名白名单）证明 webhook 零发送。
- **Rationale**: 课件 harness 明确要求"mock 底层执行器，verify(executor, never())"与 `InOrder`；父 POM 的 `spring-boot-starter-test` 已带 Mockito。真实实现测试与替身测试分工：前者验规则，后者验调用顺序。
- **Alternatives considered**: 全部用真实实现 + JDK HttpServer——能验零发送，但无法简洁地钉死"enforce 先于 send"；全部 mock——规则细节覆盖不足。两者结合。

### D-11 测试方法名英文 + `@DisplayName` 保留课件原文

- **Decision**: 所有新测试方法用英文驼峰（如 `relativePathTraversal_isRejected`），课件中文用例名放入 `@DisplayName`。
- **Rationale**: 项目要求（第 24 节 skill 写中门禁）；便于对号课件 harness。
- **Alternatives considered**: 中文方法名——违反仓库测试命名约定；不做。

### D-12 不新增 Spring 上下文装配测试

- **Decision**: 不为沙箱新增 `@SpringBootTest` 装配测试；依赖既有 `ProviderAssemblyTest` 等全上下文测试覆盖 bean 装配，`mvn clean verify` 为最终门禁。
- **Rationale**: 现有 boot 装配测试会加载 `ToolConfiguration` 与组件扫描，若属性未启用或 bean 冲突，上下文直接失败；新增专用装配测试会增加启动成本，且不在课件交付物内。
- **Alternatives considered**: `ApplicationContextRunner` 轻量测试——可选，但属性绑定已在 `WhitelistSandboxTest` 的构造参数层面覆盖，收益有限；不做。
