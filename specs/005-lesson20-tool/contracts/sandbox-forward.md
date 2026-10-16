# Contract: Sandbox 前向五件（本节定死签名，24 节 WhitelistSandbox 沿用）

**来源**: 课件 §二/§三（"所有工具执行前都要过安全校验……先以接口调用形式接入"）+ FR-003 + 技术方案 §6.7。

## 五件签名（逐字保真，软门禁 #2）

```java
// com.sableos.tool.sandbox
public interface Sandbox {
    void enforce(SandboxAction action);   // 校验不过抛 SandboxViolationException
}

public record SandboxAction(ActionType type, String target) {}   // target：路径 / 可执行文件 / URL

public enum ActionType {
    FILE_READ,      // 文件读（路径）
    FILE_WRITE,     // 文件写（路径）
    SHELL_COMMAND,  // Shell 命令（可执行文件）
    HTTP_REQUEST    // HTTP 请求（URL/域名）
}   // 四值定死：本节交付，24 节 WhitelistSandbox 直接沿用，不增不改

public class SandboxViolationException extends RuntimeException {
    public SandboxViolationException(String message) { super(message); }
}

public class PermissiveSandbox implements Sandbox { /* 放行一切，每次放行 LOG.warn 留痕 */ }
```

## 行为契约

| # | 契约 | 守点 |
| --- | --- | --- |
| S1 | 每个**涉外**工具方法第一行调用 `enforce`（文件→路径、Shell→可执行文件、HTTP→URL）；`ask_user` 无涉外 IO 不设校验位 | FR-003 硬规矩（课件"写在执行的第一步"） |
| S2 | 校验抛违规异常时**外部动作零发生**：文件未动 / 命令未跑 / 请求未发 | US2.4 / SC-002；harness 以"拒绝型 Sandbox 替身 + 动作痕迹断言（文件不存在/服务器零请求）"钉死 |
| S3 | 工具构造**强制要求** Sandbox 参数（`Objects.requireNonNull` 风格）——不存在"没有校验器就裸奔"的构造形态 | Edge Cases 明文 |
| S4 | `PermissiveSandbox` 放行**每次**记 `LOG.warn`（含 type 与净化后 target），绝不静默 | 本节可运行性装配的底线（24 节替换） |
| S5 | target 为用户可控字符串：入日志前 CRLF 净化（`replaceAll("[\\r\\n]", "_")` 形态——AGENTS.md §5.2 / findsecbugs 只认该写法）；`SandboxAction` 自身不做防御拷贝（target 为工具侧构造的字符串） | 安全门禁 |
| S6 | 违规异常**上抛**穿出工具方法（不转失败结果）——课件模板 `assertThrows(RuntimeException.class, () -> tool.execute(...))` 口径；执行层 `ToolExecutor` 对真实链路的异常统一收敛为失败结果 + 审计 | 课件 harness 逐条保真 |
| S7 | 24 节接线口径（登记）：`WhitelistSandbox` 实现本接口替换 `PermissiveSandbox`；`FILE_READ`/`FILE_WRITE` 同路由 checkFilePath、`SHELL_COMMAND` 查命令白名单、`HTTP_REQUEST` 查域名白名单——**本节不实现任何规则** | 接口先行，实现本体 23/24 节 |

## Harness 映射

| 测试 | 断言 |
| --- | --- |
| `FileToolsTest.readFile_outsideWhitelistIsBlocked` 等各工具"越界会被拦"条 | 拒绝型 Sandbox（`enforce` 直接抛 `SandboxViolationException`）→ `assertThrows` + 目标文件未被创建/未被改动（S2/S6） |
| `HttpToolsTest.httpGet_domainOutsideWhitelistIsBlocked`（课件模板 `http_get_命中白名单外域名应被拦下`） | `assertThrows(RuntimeException.class, () -> tool.execute(...))`（课件原文进 `@DisplayName`） |
| `SimpleSandboxTest`（并入各工具测试的替身，不单列类） | `PermissiveSandbox.enforce` 放行不抛（装配可运行性） |

**不做的**（边界，防越界实现）：`WhitelistSandbox`/路径解析/符号链接防绕/出网管控一律 23/24 节——本节出现这些规则的实现即越界，review 时按 S7 检查。
