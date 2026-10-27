# Contract: Sandbox 白名单校验

**Feature**: `007-lesson24-sandbox` | **Date**: 2026-10-27

本契约固定本节对外暴露的类型、配置键与接线行为。除契约点名的改动外，第 20 节已有的 `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` 签名逐字不变。

## 1. 抽象契约（沿用第 20 节，不改）

```java
public interface Sandbox {
    void enforce(SandboxAction action);
}

public record SandboxAction(ActionType type, String target) {}

public enum ActionType {
    FILE_READ, FILE_WRITE, SHELL_COMMAND, HTTP_REQUEST
}

public class SandboxViolationException extends RuntimeException {
    public SandboxViolationException(String message) { super(message); }
}
```

| 编号 | 契约 |
|---|---|
| S1 | `Sandbox` 只有一个方法 `enforce(SandboxAction)`；不得新增方法 |
| S2 | `SandboxAction` 只有 `type` 与 `target` 两个分量；不得加入白名单/容器/镜像等实现词 |
| S3 | `ActionType` 固定四值，不得增删改 |
| S4 | 校验不通过抛 `SandboxViolationException`，消息点名目标 |
| S5 | 校验通过返回 void，调用方继续执行原动作 |

## 2. 实现契约（新增 `WhitelistSandbox`）

| 编号 | 契约 |
|---|---|
| W1 | `WhitelistSandbox` 是核心阶段唯一生产实现，标注 `@Component`，构造注入三个属性记录 |
| W2 | 三个私有校验方法 `checkFilePath` / `checkShellCommand` / `checkHttpUrl` 不对外暴露 |
| W3 | `FILE_READ` 与 `FILE_WRITE` 同路由到 `checkFilePath` |
| W4 | `checkFilePath` 对目标与白名单根都执行 `toAbsolutePath().normalize()`，用 `startsWith` 按路径段比较；`..` 不得爬出根 |
| W5 | `checkShellCommand` 取 `strip()` 后首个 token，精确、大小写敏感比对 |
| W6 | `checkHttpUrl` 要求 `http`/`https` scheme 与非空 host；host 与模式统一小写；`*.` 模式按 `.` 边界后缀匹配 |
| W7 | 空/缺失配置列表按空集合处理，导致该类动作全部拒绝；null 与空白条目跳过 |
| W8 | 不新增日志；拒绝原因只进异常消息，由执行层写入审计 |
| W9 | 不实现 SMTP 校验；`smtp.allowed_endpoints` 不在本节读写 |

## 3. 配置契约

| 键 | 绑定 | 空值语义 | 示例 |
|---|---|---|---|
| `file.allowed_paths` | `FileSandboxProperties.allowedPaths` | 全拒 | `- .sableos/agents/daily-tech-digest` |
| `shell.allowed_commands` | `ShellSandboxProperties.allowedCommands` | 全拒 | `- python` |
| `http.allowed_domains` | `HttpSandboxProperties.allowedDomains` | 全拒 | `- api.open-meteo.com`、`- "*.feishu.cn"` |

- 三个属性记录均位于 `com.sableos.tool.sandbox`，`@ConfigurationProperties` 前缀分别为 `file` / `shell` / `http`。
- 由 `sableos-boot` 的 `ToolConfiguration` 通过 `@EnableConfigurationProperties` 启用。
- 配置在启动时绑定，运行期只读。

## 4. 工具接线契约

| 工具 | 调用点 | 动作类型 | 目标 | 失败语义 |
|---|---|---|---|---|
| `FileTools.readFile` | 方法首行 | `FILE_READ` | `path` | 抛违规异常，文件零打开 |
| `FileTools.writeFile` | 方法首行 | `FILE_WRITE` | `path` | 抛违规异常，文件零写入 |
| `FileTools.listDir` | 方法首行 | `FILE_READ` | `path` | 抛违规异常，目录零列出 |
| `FileTools.editFile` | 方法首行 | `FILE_WRITE` | `path` | 抛违规异常，文件零改动 |
| `FileTools.grep` | 方法首行 | `FILE_READ` | `path` | 抛违规异常，零遍历 |
| `FileTools.glob` | 方法首行 | `FILE_READ` | `path` | 抛违规异常，零遍历 |
| `ShellTools.shell` | 方法首行 | `SHELL_COMMAND` | `executable` | 抛违规异常，进程零启动 |
| `HttpTools.httpGet` | 方法首行 | `HTTP_REQUEST` | `url` | 抛违规异常，请求零发出 |
| `HttpTools.httpPost` | 方法首行 | `HTTP_REQUEST` | `url` | 抛违规异常，请求零发出 |
| `WebSearchTools`（经 `SearchProvider`） | 引擎入口 | `HTTP_REQUEST` | endpoint | 抛违规异常，搜索零发出 |
| `NotifyTools.execute` | 渠道解析成功后、`adapter.send` 前 | `HTTP_REQUEST` | `target.config().get("url")` | 抛违规异常，webhook 零发送 |

- 上述接线在 19/20 节已完成；本节只把临时放行实现替换为白名单实现，不修改调用点。
- `NotifyTools` 的顺序不变量：`enforce` 必须早于 `send`，由 `NotifyToolsTest` 的 `InOrder` 断言钉死。

## 5. 审计契约（复用既有路径，不改）

| 编号 | 契约 |
|---|---|
| A1 | 违规异常从工具方法上抛，由 `ToolExecutor` 既有 `catch (RuntimeException)` 收敛 |
| A2 | 审计记录 `success=false`，`error_message` 为违规异常的完整消息 |
| A3 | 不为 Sandbox 新增独立审计表、审计方法或审计旁路 |
| A4 | `ToolExecutor` 的执行顺序与返回值结构不变；仅更新过期注释 |

## 6. 测试契约

| 测试类 | 守点 |
|---|---|
| `WhitelistSandboxTest` | 文件路径：允许 / 拒绝 / `..` 穿越 / 相似前缀 / 相对根；Shell：允许 / 拒绝 / 前导空白 / 大小写 / 首 token；HTTP：精确 / 通配 / 形似域 / 裸域 / 不可解析 / 无 host / 非 HTTP(S) / 大小写；三类空配置 deny-all |
| `NotifyToolsTest` | 无渠道报错；缺省渠道取第一个；真实白名单拒绝时 webhook 零发送；`InOrder` 断言 enforce 先于 send |
| `FileToolsTest` / `ShellToolsTest` / `HttpToolsTest` | 既有"白名单外被拦 + IO 零发生"用例保持全绿 |
| `ProviderAssemblyTest` 等 boot 装配测试 | 新 bean 与属性绑定可随上下文升起；既有测试保持全绿 |
