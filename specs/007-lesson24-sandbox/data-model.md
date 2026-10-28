# Phase 1 Data Model: Sandbox 白名单校验

**Feature**: `007-lesson24-sandbox` | **Date**: 2026-10-27

本节不新增数据库表、不修改 Flyway 迁移。数据模型只包含内存中的值对象、配置绑定与校验规则。

## 1. 值对象

### 1.1 `SandboxAction`（沿用第 20 节，不改）

| 字段 | 类型 | 含义 | 约束 |
|---|---|---|---|
| `type` | `ActionType` | 动作类型 | 非空；四值之一 |
| `target` | `String` | 动作目标 | 解释由 `type` 决定：路径 / 可执行文件 / URL |

### 1.2 `ActionType`（沿用第 20 节，不改）

| 值 | 目标语义 | 路由 |
|---|---|---|
| `FILE_READ` | 文件路径 | `checkFilePath` |
| `FILE_WRITE` | 文件路径 | `checkFilePath`（与读共用路径白名单） |
| `SHELL_COMMAND` | 可执行文件 / 命令串 | `checkShellCommand` |
| `HTTP_REQUEST` | URL | `checkHttpUrl` |

### 1.3 `SandboxViolationException`（沿用第 20 节，不改）

- 继承 `RuntimeException`；
- 消息点名被拒目标；
- 由工具方法上抛，经 `ToolExecutor` 收敛为 `ToolResult.failure` 并写入 `tool_invocations`。

### 1.4 `WhitelistSandbox`（新增，唯一生产实现）

| 内部字段 | 类型 | 来源 | 规范化 |
|---|---|---|---|
| `allowedRoots` | `List<Path>` | `file.allowed_paths` | 去空条目；`toAbsolutePath().normalize()` |
| `allowedCommands` | `Set<String>` | `shell.allowed_commands` | 原样精确比对；`Set.copyOf` |
| `allowedDomainPatterns` | `List<String>` | `http.allowed_domains` | 去空条目；统一小写 |

## 2. 配置契约

| 配置键 | 类型 | 默认 | 语义 |
|---|---|---|---|
| `file.allowed_paths` | `List<String>` | `[]` | 允许读写的目录/文件根；空 = 全拒 |
| `shell.allowed_commands` | `List<String>` | `[]` | 允许的可执行文件首 token；空 = 全拒 |
| `http.allowed_domains` | `List<String>` | `[]` | 允许的域名或 `*.` 子域模式；空 = 全拒 |
| `smtp.allowed_endpoints` | `List<String>` | `[]` | 扩展阶段邮件通知预留；本节不读取、不校验 |

绑定方式：三个独立 `@ConfigurationProperties` 记录（`prefix = "file"` / `"shell"` / `"http"`），字段名与 YAML 下划线键按 Spring Boot relaxed binding 对应；由 `sableos-boot` 的 `ToolConfiguration` 通过 `@EnableConfigurationProperties` 启用。列表为 null 时按空列表处理。

## 3. 校验规则

### 3.1 路径规则（`FILE_READ` / `FILE_WRITE`）

输入：`rawPath: String`。

1. `rawPath` 为 null / 空白 → 拒绝。
2. `target = Path.of(rawPath).toAbsolutePath().normalize()`。
3. 若存在任一 `root ∈ allowedRoots` 使 `target.startsWith(root)` → 放行；否则拒绝。
4. 空 `allowedRoots` → 全部拒绝。

不变量：

- 目标等于根 → 放行（`startsWith` 自反）。
- `root/../outside` → normalize 后不在根下 → 拒绝。
- `root-evil/x` → 路径段比较不命中 `root` → 拒绝。
- 相对目标/相对根均按 JVM 当前工作目录解析为绝对路径后再比较。

### 3.2 命令规则（`SHELL_COMMAND`）

输入：`command: String`。

1. `command` 为 null / 空白 → 拒绝。
2. `firstToken = command.strip().split("\\s+")[0]`。
3. `allowedCommands.contains(firstToken)` → 放行；否则拒绝。
4. 空 `allowedCommands` → 全部拒绝。

不变量：

- `"  ls"` 与 `"ls"` 等价（前导空白被去除）。
- `"ls -la"` 只看 `ls`。
- `"LS"` 与 `"ls"` 不等价（大小写敏感）。

### 3.3 域名规则（`HTTP_REQUEST`）

输入：`rawUrl: String`。

1. `rawUrl` 为 null / 空白 → 拒绝。
2. `URI.create(rawUrl)`；解析抛 `IllegalArgumentException` → 拒绝（转抛安全违规异常）。
3. scheme 必须为 `http` / `https`（大小写不敏感）；否则拒绝。
4. `host = uri.getHost()`；null / 空白 → 拒绝。
5. `normalizedHost = host.toLowerCase(Locale.ROOT)`。
6. 对每个模式 `pattern`（构造时已小写）：
   - 以 `*.` 开头 → `normalizedHost.endsWith(pattern.substring(1))`；
   - 否则 → `normalizedHost.equals(pattern)`。
7. 任一命中 → 放行；否则拒绝。
8. 空 `allowedDomainPatterns` → 全部拒绝。

不变量：

- `*.example.com` 命中 `api.example.com`、`a.b.example.com`（按字符串后缀，多级子域同样命中）。
- `*.example.com` 不命中 `evil-example.com`（缺少点号边界）。
- `*.example.com` 不命中裸域 `example.com`。
- 主机名比较大小写不敏感。

## 4. 状态与生命周期

- 白名单配置在应用启动时绑定，运行期只读；本节不支持热更新。
- 每次工具调用独立执行 `enforce`，无状态、可并发。
- 校验失败不改变任何持久化状态，只产生一次失败的工具调用审计记录（由既有执行层完成）。

## 5. 数据量假设

- 每个白名单列表预期为个位到数十条；校验为线性扫描，无索引/缓存需求。
- 不引入新的存储、迁移或实体。
