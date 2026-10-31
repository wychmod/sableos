# 验收报告：第 30 节（014-lesson30-dynamic-agent）

**分支**：`014-lesson30-dynamic-agent` ｜ **日期**：2026-10-31 ｜ **口径**：以课件 §5「实现回写」为准（已获项目方确认）

## 0. 口径声明

课件 §5 明确「以实现为准」，且 §5 与《技术方案》§11.3 原始设计冲突；经确认后**本节以实现回写 §5 为准**，并把 §5 的四块增补（per-agent 记忆、固定会话、可编辑文件、生成/编辑一对端点）一并纳入，同时在《技术方案》§7.2/§7.3 与原 §11.3 处补记偏差。

## 1. 交付物对照（课件「本节交付物」）

| 课件交付物 | 实际落点 | 状态 |
| --- | --- | --- |
| `AgentApiController` 全部端点 | `sableos-web/.../api/AgentApiController.java`（11 个方法） | ✅ |
| `WorkspaceApiController`（tree / file 读 / file 写，防穿越） | `sableos-web/.../api/WorkspaceApiController.java` | ✅ |
| `AgentLifecycleService`（create 含回滚 / register / get / update / delete / generate） | `sableos-core/.../agent/AgentLifecycleService.java` | ✅ |
| `WorkspaceWatcher`（启动全量扫 + 实时监听） | `sableos-core/.../agent/WorkspaceWatcher.java` | ✅ |
| `AgentScheduler.unregisterProfile` | `sableos-core/.../scheduler/AgentScheduler.java` | ✅ |
| `agentStore` 的 write / archive / delete | `sableos-core/.../agent/AgentStore.java` | ✅ |
| `AgentView` / `FileNode` / 请求体 DTO | `sableos-web/.../api/dto/`（10 个新 DTO） | ✅ |
| `AgentLifecycleServiceTest` | 同目录，13 例 | ✅ |
| `WorkspaceWatcherTest` | 同目录，7 例 | ✅ |
| `WorkspaceApiControllerTest` | 同目录，9 例 | ✅ |
| `AgentApiControllerTest` | 同目录，12 例 | ✅ |
| `GenerateTest` | `sableos-core/.../agent/GenerateTest.java`，10 例 | ✅ |
| `.sableos/archive/` 目录 | `AgentStore.archive`（同名追加时间戳，绝不覆盖） | ✅ |
| 前端「Agent 管理」「工作区」两页（§5.4 收敛） | `AgentsPage.vue`（列表 + 新建 + 删除）+ `AgentDetailPage.vue`（五页签）+ `FileTreeNode.vue` | ✅ |

## 2. §5 增补四项

| 增补 | 落点 |
| --- | --- |
| per-agent 记忆（§5.2.1） | `ToolExecutionContext`（core）+ `ToolExecutor` 置入 + `MemoryServiceImpl` 读路径上下文 + `MarkdownMemoryStore` 路径解析；移除全局 `/memory`，新增 `GET /agents/{name}/memory` |
| 固定会话（§5.2.2） | `GET /agents/{name}/session`、`POST /agents/{name}/session/messages`；三元组 `admin` / `console` / `<name>`，`session_id` 仍只由 `SessionManager.sessionId` 生成 |
| 文件可编辑（§5.2.3） | `POST /workspace/file`；写 `AGENT.md` 走 `saveFiles` 重注册，不裸写盘 |
| 生成/编辑一对端点（§5.2.4） | `POST /agents/{name}/generate-files`（不落盘）+ `POST /agents/{name}/files`（保存生效） |

## 3. 端点清单（14 个）

见 `specs/014-lesson30-dynamic-agent/contracts/rest-api.md` 与 README「REST API」表：创建/列表/详情/更新/删除（5）、调用（既有 1，不变）、记忆/会话/会话消息（3）、生成/保存（2）、工作区 tree/file 读/file 写（3）。

## 4. 构建与测试

### 4.1 全量 `mvn clean verify`

见第 7 节实时输出（末次运行）。

### 4.2 本节新增/扩展 harness

| 类 | 例数 | 覆盖要点 |
| --- | --- | --- |
| `AgentStoreTest` | 9 | 写入防穿越（含 Windows 前导斜杠坑）、归档绝不覆盖、删除幂等、名字与路径边界、`readAll` 跳过库文件 |
| `AgentLifecycleServiceTest` | 13 | create 顺序、同名零写入、失败回滚、delete 定序、update 先注销再注册、生成不落盘不注册 |
| `GenerateTest` | 10 | 草稿可被同一解析器接受、多文件切分与围栏剥离、路径拒绝、503/400 语义、提示词带真实工具清单、既有 Agent 沿用 provider |
| `WorkspaceWatcherTest` | 7 | 丢目录即登记、删目录即注销、坏目录只告警、改文件即刷新、根/子目录事件筛选、真 `WatchService` 端到端 |
| `AgentSchedulerRegisterTest`（扩展） | +3 | `unregisterProfile` 取消句柄并退役、只影响本 Agent、幂等 |
| `WorkspaceApiControllerTest` | 9 | 目录树、读文件、三类穿越全 400、404、写 AGENT.md 走 lifecycle、写其它文件直写 |
| `AgentApiControllerTest` | 12 | 薄转发、400/404、503 透传、门面预校验 |
| `MarkdownMemoryStoreTest`（扩展） | +4 | per-agent 路径、无上下文回退、两 Agent 隔离、非法名回退 |
| `ToolExecutorTest`（扩展） | +1 | 执行期上下文置入、执行后清除 |

既有受影响的 harness 已同步：`MockAgentE2ETest`、`ScheduledTaskE2ETest`、`WebSmokeIT`、`RestartRecoveryIT`、`HumanTriggerFlowIT`、`MockProviderFlowTest`（`/memory` → `/agents/{name}/memory`；记忆文件断言改 `agents/<name>/MEMORY.md`）。

## 5. 真实运行验证（非仅单测）

以 mock provider 起真实 fat JAR（`-Dsableos.root=/tmp/l30ws`），逐条走 HTTP：

| 验证项 | 结果 |
| --- | --- |
| `POST /agents` 创建 | 200，磁盘出现 `AGENT.md` / `REFERENCE.md` / `scripts/example.py` |
| 同名再建 | 400，零文件新增 |
| 手工丢目录 | 3 秒内出现在列表（无重启） |
| 改 `AGENT.md` | 描述即时更新 |
| 删目录 | 立刻从列表消失 |
| `GET /workspace/tree` | 返回 `agents` + `archive` 两棵树 |
| 穿越 `../../etc/passwd` / `/etc/passwd` / `sableos.db` | 全 400 |
| `POST /workspace/file` 改 AGENT.md | 200，描述即时生效 |
| `GET /agents/{name}/session` | `admin:console:weather-daily` |
| 固定会话发消息 | 200，落库并在历史里可见 |
| `GET /agents/{name}/memory` | 返回该 Agent 的 `MEMORY.md` 内容；文件确实在 `agents/weather-daily/MEMORY.md` |
| `POST /agents/{name}/generate-files`（无 `author.model`） | **503** 且 message 可读 |
| `DELETE /agents/{name}` | 200，目录进 `archive/`，列表清空 |
| `/admin/` 与 `/admin/agents/<name>` | 200（SPA 回落正常） |

### 5.1 浏览器验证（Playwright）

9 个步骤全部通过、**控制台零错误**：列表 → 详情 → 生成页签 → 文件页签（打开 `AGENT.md` 读到内容）→ 会话页签（发消息并收到答复气泡）→ 记忆页签 → 新建对话框创建 → 新 Agent 详情 → 记忆页（per-agent）。

**视觉验证方式说明**：本次运行的模型无法直接读取图片，故改用程序化验证——(a) 关键元素几何（`.profile-card` / `.tab-bar` / `.panel` 等）均无重叠、`overflowX=false`、无横向滚动；(b) 页面像素采样得 173 种不同颜色、平均亮度 10（深色主题，非空白/非全黑）。截图已生成后清理，未入库。

## 6. 六项全局不变量自查（H4）

| # | 不变量 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | 三处地方（core / web / boot）不产生循环依赖 | ✅ | `ArchitectureTest` 全绿；`ArchitectureTest` 的 `NON_CORE_MODULES` 未新增条目（本节零新增模块） |
| 2 | `mvn clean verify` 全绿（格式 / P3C / Checkstyle / SpotBugs + FindSecBugs） | ✅ | 见第 7 节 |
| 3 | 响应信封 5 字段不变 | ✅ | 未改 `ApiResponse`；端点全部走 `ApiResponse.ok` |
| 4 | traceId 跨线程传播 | ✅ | 本节新增线程只有单个守护线程监听循环（无 `TaskDecorator` 新增），生成走同步 `ProviderService.chat`（同线程）；`ContextClosedEvent` 关闭不涉及传播 |
| 5 | 审计 day one 落库 | ✅ | 生成调用走 `ProviderService.chat` → `llm_calls`（`session_id = agent-author:<name>`）；工具执行仍唯一经 `ToolExecutor` |
| 6 | 60 秒上限 | ✅ | 固定会话发消息复用既有 `AgentCallExecutor`（60s）；生成不使用该上限（一次模型调用，由 provider 侧超时兜底） |

## 7. AGENTS §5.1 / §5.2 专项自查

| 条款 | 结论 |
| --- | --- |
| 5.1 #3/#4 自实现 ReAct、禁用 Spring AI 自动 tool 执行 | 未触碰 `ReActLoop` / `ToolExecutor` 的执行语义；仅在其前后加 `ToolExecutionContext` 置入/清除 |
| 5.1 #5 Tool 单模块、Agent 目录归 core | 4 个新类全落 `com.sableos.core.agent`；未新增 tool 模块实现 |
| 5.1 #6 审计落库、表结构唯一权威是 Flyway | 本节**零新增表**、零 migration |
| 5.1 #8 Provider 显式映射 | 生成用 provider 由配置键显式给出 / 取清单首个（非类型扫描） |
| 5.2 阻塞调用不进 `synchronized` | 新增代码零 `synchronized`；`AgentStore` 无锁 |
| 5.2 MDC 子线程不继承 | 监听线程不用 MDC；生成在请求线程内同步 |
| 5.2 只允许一个 `TaskDecorator` | 未新增 |
| 5.2 不用 `UUID.randomUUID()` 生成 traceId | 未涉及 |
| 5.3 用户可控字符串入日志前净化 | `AgentLifecycleService` / `WorkspaceWatcher` / `AgentStore` 的日志均经 `replaceAll("[\r\n]", "_")`（保持字面形态） |
| 5.3 响应信封不加 `@JsonInclude(NON_NULL)` | 未改 |

## 8. 课件偏差记录

| 课件写法 | 实际实现 | 原因 |
| --- | --- | --- |
| `providerService.complete(prompt, text)` | `providerService.chat(genSessionId, 临时 Profile, ProviderRequest)` | 仓库 `ProviderService` 只有 `chat`；`chat` 天然落 `llm_calls` |
| 生成提示词见 `docs/prompt/prompt.md` | 常量 `AGENT_AUTHOR_PROMPT` 内置在 `AgentLifecycleService` | 仓库不存在 `docs/prompt/`；已按项目方选择「内置常量」 |
| 课件引用 `images/class-30-1.svg` | **未补** | 仓库无该文件；按项目方选择暂不补，仅在此记录 |
| `agentScheduler.unregisterProfile(...)` 只给语义 | 按 Profile 名筛句柄表 + `retire` | 课件只给语义未给代码 |
| 脚手架 provider 缺省 | 无已登记 provider 时 `POST /agents` 返回 400 可读原因（原本会派生失败只留「provider.name 为空」） | 实测发现的可用性问题，属本节的健壮性补齐 |

## 9. 遗留与下一步

1. **`images/class-30-1.svg` 未绘制**：课件引用缺失，已在第 8 节记录；如需补图另开任务。
2. **生成端点需要 `sableos.author.model`**：无该配置时只能走「模板创建」，这是有意的失败快路径（503 可读），不是缺陷。
3. **管理台前端构建链不在 Maven**：本次已手工 `npm run build` 并入库产物；后续改前端仍需手工重建（AGENTS §10 已记）。
