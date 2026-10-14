# Contract: 上下文组装（PromptBuilder + ContextLoader）

**Feature**: 002-lesson17-react | 契约类型：进程内 Java 类（`com.sableos.core.prompt` / `com.sableos.core.context`）

组装是两部分：`ContextLoader`（读文件 → system prompt 文本）与 `PromptBuilder`（拼 system + 记忆 + 历史 + 工具 → `ProviderRequest`）。

## 1. PromptBuilder（`com.sableos.core.prompt`）

```java
public final class PromptBuilder {
  public PromptBuilder(ContextLoader contextLoader, ToolCatalog toolCatalog, MemoryProvider memoryProvider);
  public ProviderRequest build(Session session, Profile profile);
}
```

**返回**：第 16 节中立 DTO `ProviderRequest(String content, List<SableTool> availableTools)`——**第 16 节契约零改动**（research D-02）。

**组装顺序（content 的段顺序，固定，FR-006）**：

```text
1. system prompt
     = contextLoader.systemPrompt(profile)   （角色设定 + Agent 正文 + bootstrap + skills 说明）
     + "\n当前时间：" + <当前日期时间>          （D-13，追加在 system prompt 末尾）
2. 记忆段       = memoryProvider.memorySection(profile)   （null/空白 → 整段省略）
3. 会话历史     = session.messages() 的最近 N 条，按角色前缀逐条渲染（见 §1.1）
                  （N = profile.settings().maxHistoryTurns()，默认 20；一条消息 = 一轮，D-12）
段间以空行分隔；拼接为单一 String 放进 content
```

**availableTools**：`toolCatalog.toolsFor(profile)`（工具走原生 schema，不进 content）。

### 1.1 历史渲染格式（D-12）

| role | 渲染 |
| --- | --- |
| USER | `User: <content>` |
| ASSISTANT | `Assistant: <content>`（若 `toolCalls` 非空，附一行 `(请求调用工具: <name 列表>)`） |
| TOOL | `Tool[<toolName>]: <content>` |

**截断规约**：

1. 只取 `session.messages()` 的**最近 N 条**（若总数 ≤ N 则全取）；system prompt 与记忆段**不参与截断**（FR-007）。
2. 「一条消息 = 一轮」（clarify 确认）——用户消息 / 模型输出 / 工具结果**各计一条**。
3. 当前用户消息已由 `ReActLoop` 在进入循环前 `appendUserMessage`，故历史的尾部即当前用户消息——`PromptBuilder` **不重复追加**。

### 1.2 边界

| 项 | 规约 |
| --- | --- |
| `memoryProvider` 为 null 或其返回 null/空白 | 跳过记忆段，不报错（FR-006 / Edge Case） |
| 历史为空 | 只含 system prompt（+ 记忆段） |
| 不做上下文压缩 | 仅「最近 N 条」截断（边界） |

### 1.3 关键回归测试（课件 harness）

| 测试（英文方法名） | 守点 | 课件原文 |
| --- | --- | --- |
| `build_historyExceedsLimit_keepsOnlyLatestNMessages` | 历史超 N 条被截断、system prompt 仍在（SC-003） | 历史超 N 轮被截断 |
| `build_systemPromptEndsWithCurrentDateTime` | system prompt 末尾含当前日期时间（FR-006） | system prompt 末尾含当前日期时间 |

## 2. ContextLoader（`com.sableos.core.context`）

```java
public final class ContextLoader {
  public ContextLoader(Path agentsDir, Path bootstrapDir, Path skillsRoot);
  public String systemPrompt(Profile profile);
}
```

**职责（《技术方案》§8.3，每次调用现读、不缓存，FR-013）**：

| 段 | 内容 | 缺失处理 |
| --- | --- | --- |
| 角色设定 | `profile.identity().prompt()`（非空则注入） | 无 → 省略该行 |
| Agent 正文 | `<agentsDir>/<name>/AGENT.md` 的**正文**（剥离 `---` frontmatter 围栏），**全量注入** | 文件缺失 → 至少 WARN（不静默） |
| 启动信息 | `profile.bootstrap()` 列出的每个文件，**全量注入** | 单文件缺失 → 至少 WARN（不静默） |
| Skill 说明 | 扫 `<agentsDir>/<name>/skills/*` **相对软连接**：每项注入 Skill 的 `name`/`description`/本地绝对读取路径（**正文不预载**） | 显式引用但缺失（软连接断裂 / 无 `SKILL.md`）→ **报错并点名**（FR-014） |

**行为规约**：

1. **无缓存**：`systemPrompt` 每次调用都重新读文件（FR-013 / SC-006）——改文件后下一次组装立即读到新内容。
2. **frontmatter 剥离自持**：只切 `---` 首围栏到次 `---` 之间的部分，**不做 YAML 解析、不调 `AgentLoader` 私有方法**（research D-11）。
3. **Skill 软连接校验**：软连接目标须落 `skillsRoot` 内（防止越界），且目标含 `SKILL.md`；`SKILL.md` 的 frontmatter 提供 `name`/`description`。
4. **日志净化**：任何入日志的路径/名称先 `replaceAll("[\\r\\n]", "_")`（AGENTS.md §5.2）。
5. 参数 `bootstrapDir` 用于定位 `Profile.bootstrap()` 的相对文件（缺省 = 工作区根 `.sableos/`）。

### 2.1 关键回归测试（课件 harness）

| 测试（英文方法名） | 守点 | 课件原文 |
| --- | --- | --- |
| `systemPrompt_fileChanged_reflectsNewContentImmediately` | 改文件后下次组装立即读到新内容（无缓存，SC-006） | 改文件后下一次组装立即读到新内容（无缓存） |
| `systemPrompt_missingSkillBinding_throwsNamed` | Skill 引用缺失报错（FR-014） | Skill 引用缺失报错 |
| `systemPrompt_missingBootstrap_logsWarn` | Bootstrap 缺失至少 WARN（FR-014） | Bootstrap 缺失 WARN |
