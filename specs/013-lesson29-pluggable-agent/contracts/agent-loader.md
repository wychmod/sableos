# Contract: AgentLoader（目录派生与校验）

**Feature**: `specs/013-lesson29-pluggable-agent` | 类：`com.sableos.core.agent.AgentLoader`

## 构造

```java
public AgentLoader(Path agentsDir, Set<String> knownProviders)
public AgentLoader(Path agentsDir, Set<String> knownProviders, Set<String> knownTools)
```

- `agentsDir`：Agent 根目录，其下每个一级子目录是一个 Agent。
- `knownProviders`：`sableos.providers` 清单里的 provider 名。**派生时强校验**，未命中即拒绝该 Agent。
- `knownTools`：`ToolRegistry` 里的工具名。**仅用于告警**，未命中不拒绝。传空集等价于不做工具检查（保留两参构造器的既有行为）。

## loadAll

```java
ProfileRegistry loadAll()
```

| 输入状况 | 行为 |
| --- | --- |
| 根目录不存在 | INFO 日志，返回空注册表，不抛异常 |
| 根目录下普通文件 | 忽略（只处理 `Files::isDirectory`） |
| 某子目录非法（缺 `AGENT.md` / YAML 坏 / `name` ≠ 目录名 / provider 未登记 / model 空） | ERROR 日志点名目录与原因，跳过该 Agent；继续处理其余 |
| 某子目录合法但 `tools` 含未注册能力 | WARN 日志点名 Agent 与工具名，仍登记 |
| 全部合法 | 注册表条目数 == 合法目录数 |

**不变量**：`loadAll` 永不抛异常（磁盘不可读也只记 ERROR 返回空表）。

## deriveProfile(Path)

```java
Profile deriveProfile(Path agentDir) throws IOException
```

- 读取 `agentDir/AGENT.md`，以 `agentDir.getFileName()` 作为目录名参与校验。
- 缺文件抛 `IOException`；结构违规抛 `IllegalArgumentException`（消息点名原因）。
- **运行期登记必须走本方法**，以保证与启动扫描同异常同消息（research D-07）。

## deriveProfile(String, String)

```java
Profile deriveProfile(String directoryName, String agentMarkdown)
```

既有签名保留，供纯字符串输入的测试与内部复用。`deriveProfile(Path)` 读文件后委派给它。

## 校验清单（本节新增项加粗）

| 校验 | 异常 | 消息要点 |
| --- | --- | --- |
| `AGENT.md` 存在 | `IOException` | 「目录下缺少 AGENT.md」 |
| frontmatter 围栏闭合 | `IllegalArgumentException` | 「缺少 frontmatter」「围栏未闭合」 |
| YAML 可解析 | `IllegalArgumentException` | 「frontmatter YAML 解析失败：…」 |
| `name` 非空 | `IllegalArgumentException` | 「frontmatter 缺少 name」 |
| `name` == 目录名 | `IllegalArgumentException` | 「name(…) 与目录名(…) 不一致」 |
| `provider` 段存在 | `IllegalArgumentException` | 「Agent X 缺少 provider 段」 |
| `provider.name` 在清单内 | `IllegalArgumentException` | 「引用的 provider 未在 sableos.providers 清单登记：…」 |
| `provider.model` 非空 | `IllegalArgumentException` | 「provider.model 为空（provider=…）」 |
| **`tools` 含未注册能力** | **不抛** | **WARN：「Agent X 引用了未注册的能力: Y」** |

## 不做的事

- 不解析 `skills/`（那是 `ContextLoader` 的职责，且软连接校验在读取时进行）。
- 不解析 `scripts/`、`REFERENCE.md`（它们不进运行配置，按需取用）。
- 不校验 `notify_channels`（该字段不存在于 frontmatter）。
