# Quickstart: 第 20 节 Tool 体系——验收向导

**Feature**: `005-lesson20-tool` | 契约细节见 [contracts/](./contracts/)、实体与不变量见 [data-model.md](./data-model.md)、决策依据见 [research.md](./research.md)。

## 前置

- JDK 21 + Maven 3.9+（本地仓库 `D:\maven\Repository`）；分支 `005-lesson20-tool`。
- 零 key、零出网可跑全部自动化验证（假 HTTP 服务 = JDK 内置 `HttpServer`；MCP 全程 mock，不真起子进程）。
- 首次改动后先 `mvn spotless:apply`（格式违规直接挂构建）。

## 1. 全量门禁（实现完成的定义）

```bash
mvn clean verify
```

预期：**BUILD SUCCESS**——含 Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / 全部单测；前序节测试（16~19）回归全绿（跨节契约证据）。

## 2. 本节测试（harness 直跑）

```bash
mvn -B -pl sableos-tool test
```

预期：10 个测试类全绿。关键回归单测逐个对号：

| 命令片段（-Dtest=） | 守点 |
| --- | --- |
| `SableToolContractTest` | 注册表全工具契约三件套非空（参数化遍历，新工具自动纳入） |
| `ToolRegistryTest` | 三来源统一注册 + 重名拒绝 + 过滤"不多不少" |
| `FileToolsTest` | 正常 + 拦截 + edit_file 唯一匹配（找不到/多处文件不动）+ grep/glob 200 上限注明 + 二进制跳过 |
| `ShellToolsTest` | 正常 + 拦截 + 非零退出带 stderr + 超时终止 |
| `HttpToolsTest` | 课件两条模板（`http_get_应能取回响应` / `http_get_命中白名单外域名应被拦下`）+ 非 2xx 带状态码 |
| `McpToolAdapterTest` / `McpClientServiceTest` / `McpConfigLoaderTest` | 转发原样 + 结果包装（可重试）+ 坏 server 只 WARN 不炸 + 配置缺失=零/非法点名 |
| `InteractionToolsTest` / `WebSearchToolsTest` | ask_user 无人值守报错 + web_search 白名单先行（零请求） |

## 3. 装配级验证（boot，端到端）

```bash
mvn -B -pl sableos-boot test -Dtest=ReactAssemblyTest
```

预期全绿，覆盖：①零 key 上下文升起、装配 bean 齐备；②`ToolRegistry` 替换空工具表（`find("http_get")` 非 null；空 Profile 过滤为空）；③脚本化 Provider 驱动一轮端到端（真循环 + 真审计落库，含一条注册工具成功调用与一条未知名工具失败审计）。

## 4. `tool list`（轻命令，秒回）

```bash
cd sableos-cli && mvn -q -pl ../sableos-cli exec:java -Dexec.mainClass=com.sableos.cli.SableOsCli -Dexec.args="tool list" 2>/dev/null || \
java -jar sableos-boot/target/sableos-boot-*.jar tool list
```

预期：输出静态 **12 工具清单**（read_file/write_file/list_dir/shell/http_get/http_post/edit_file/grep/glob/ask_user/web_search/notify，各一行简述）+ 如实标注"运行期实时清单（含 MCP 连接结果）归 26 节 Web 管理台；轻命令不启动 Spring"。零 Spring、秒回。

## 5. H4 全局不变量自查（grep 级）

| 检查 | 命令/口径 | 预期 |
| --- | --- | --- |
| 涉外 IO 首行过校验 | 检查每个 `@Tool`/`enforce`：`grep -n "enforce(" sableos-tool/src/main/java -r` | 每涉外工具方法第一行；违规动作零发生（单测钉死） |
| 审计落库 | `ReactAssemblyTest` 逐条断言 `tool_invocations` 行 | 成败两态 + `trace_id` 非空；本节零旁路 |
| 无明文 key | `grep -rn "sk-\|api.key\|token" sableos-tool/src/main/java`（人工判读） | 无硬编码凭证；`.sableos/mcp_servers.yaml` 由使用者提供 |
| `session_id` 单点 | 本节不拼接 session_id（上下文经 `ToolExecutor` 带参） | 无散落拼装 |
| 无 Reactor / CompletableFuture / 自建线程池 | `grep -rn "CompletableFuture\|reactor\|Executors.new" sableos-tool/src` | 命中仅允许 shell 虚拟线程执行器（`newVirtualThreadPerTaskExecutor`） |
| 无 Spring AI 自动执行 | `grep -rn "ToolCallbacks\|ToolCallback" sableos-tool/src/main` | 仅 `ToolRegistry.registerAnnotated` 与 `AnnotatedToolAdapter` 两处；无自动调度 |
| CRLF 净化形态 | `grep -rn "replaceAll" sableos-tool/src/main` | `replaceAll("[\\r\\n]", "_")` 形态（勿改 Pattern/Matcher） |
| 零新对外概念 | 对照 [spec.md](./spec.md) 交付物清单逐类型核对 | 无清单外 public 类型 |

## 6. 人工项（harness 判卷之外，留给人工）

| 项 | 做法 | 说明 |
| --- | --- | --- |
| 方式一真跑 | `.sableos/skills/` 写 SKILL.md + `.sableos/mcp_servers.yaml` 连真实 MCP server，Agent 读意图调外部工具完成任务 | 依赖真模型 + 真 server；先 `cp config/mcp_servers.yaml.example .sableos/mcp_servers.yaml` 改内容 |
| 方式三真跑 | 一个 `@Tool` 示例 Bean 放进工程，经 `sableos chat` 真实调用验证（内置工具走同一条注解管道，链路由装配级测试与 chat 冒烟覆盖） | 依赖真模型；`tool list` 静态清单按 clarify #2 口径不含运行期枚举 |
| Demo 一动作 | `sableos chat` 问天气 → 模型发起 `http_get` 真请求（**需网络与白名单配置**；本节 `PermissiveSandbox` 放行必 WARN） | 真链路目检 `tool_invocations` 有成功记录 |
| 真 webhook 通知 | 配真 webhook 渠道后经 24 节接线验证（本节 notify 装配为空表） | 归 24 节后 |

## 7. 完成后（用户决定）

- 不自动 commit/push/package.sh——变更总结（改动点 / 重点 review 清单 / 如何验证）在验收报告中输出后停止，由用户决定同步时机。
