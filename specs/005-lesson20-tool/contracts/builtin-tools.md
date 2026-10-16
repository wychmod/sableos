# Contract: 内置与扩展工具（`@Tool` 注解管道，方法级契约）

**来源**: 课件 §三（`http_get` 示例逐字）+ §六（扩展五工具）+ FR-002/FR-004/FR-008/FR-009/FR-010。全部落 `com.sableos.tool.builtin`（交互/搜索的抽象与实现分别落 `interaction`/`web` 包，见 [data-model.md §6](../data-model.md)）。**每个涉外方法第一行 `enforce`**（S1）；schema 由 Spring AI 从签名自动生成。

## FileTools（6 个 `@Tool` 方法，构造 `FileTools(Sandbox sandbox)`）

| 方法 | 签名（课件语汇） | 行为契约 | 失败口径 |
| --- | --- | --- | --- |
| `readFile` | `@Tool(name="read_file") String readFile(@ToolParam(description="要读取的文件路径") String path)` | enforce(FILE_READ, path) → 非普通文件点名报错 → `Files.readString` 返回全文 | 不存在/非文件 → `IllegalArgumentException` 点名路径（不抛裸异常）；IO → `UncheckedIOException` |
| `writeFile` | `@Tool(name="write_file") String writeFile(@ToolParam("要写入的文件路径") String path, @ToolParam("要写入的内容") String content)` | enforce(FILE_WRITE, path) → `Files.writeString`（覆盖写）→ `"已写入: " + path` | **不自动建父目录**（spec 边界，模型该显式决定）；父目录缺失 → 失败点名路径 |
| `listDir` | `@Tool(name="list_dir") String listDir(@ToolParam("要列出的目录路径") String path)` | enforce(FILE_READ, path) → 非目录点名 → `Files.list` 名称排序 `\n` 连接 | 不存在 → `IllegalArgumentException` 点名 |
| `editFile` | `@Tool(name="edit_file") String editFile(@ToolParam("要编辑的文件路径") String path, @ToolParam("要被替换的原文本（必须在文件中唯一出现）") String oldString, @ToolParam("替换后的新文本") String newString)` | enforce(FILE_WRITE, path) → 读全文 → `indexOf` 唯一性检查（找不到 → 报错；`indexOf(old, first+1) >= 0` 多处 → 报错）→ 唯一才 `writeString(replace)` → `"已编辑: " + path` | 找不到/多处 → `IllegalArgumentException` 点名且**文件零字节改动**（读阶段失败，未进入写） |
| `grep` | `@Tool(name="grep") String grep(@ToolParam("要搜索的正则表达式") String pattern, @ToolParam("搜索根路径（文件或目录）") String path)` | enforce(FILE_READ, path) → `Files.walk` → 逐文件逐行 `Pattern.find` → 收集 `"文件:行号:内容"`；**200 条上限**：到限追加 `"...（已达 200 条上限，结果截断）"` 后停；单文件读取抛异常（二进制/非 UTF-8）→ **跳过该文件继续** | 根不存在 → 点名；非法正则 → `PatternSyntaxException`（JDK）上抛；无命中 → `"（无匹配）"` |
| `glob` | `@Tool(name="glob") String glob(@ToolParam("glob 通配模式，如 **/*.yaml") String pattern, @ToolParam("查找根目录") String path)` | enforce(FILE_READ, path) → `Files.walk` + `PathMatcher("glob:"+pattern)` → 最多 200 条；到限追加截断注明；`**/` 前缀对根下单段相对路径的补匹配（对齐 oryxos 已核实字段修复） | 根非目录 → 点名；无命中 → `"（无匹配）"` |

## ShellTools（`ShellTools(Sandbox sandbox)` + package-private 超时注入构造）

| 项 | 契约 |
| --- | --- |
| 方法 | `@Tool(name="shell") String shell(@ToolParam("要执行的、已在白名单中的可执行文件") String executable, @ToolParam("传给可执行文件的独立参数数组，不支持 shell 语法") List<String> arguments)` |
| 首行 | `enforce(SHELL_COMMAND, executable)` |
| 执行 | `ProcessBuilder(executable, args...)`——**不经 Shell 解释**，参数原样 argv；输出按 `Charset.defaultCharset()` 解码（Windows GBK 先例） |
| 超时 | 默认 30s（常量，package-private 构造可注入测试值）；超时 → **杀进程树**（`descendants().destroyForcibly` + 自身）→ 报"命令超时（Ns）被终止: <executable>" |
| 非零退出 | `IllegalStateException("命令退出码 N: <stderr>")` |
| 流排空 | stdout/stderr 虚拟线程并发读满（防管道写满被误判超时）；输出上限/深浅杀等 oryxos 后期特性不在本节 |

## HttpTools（`HttpTools(Sandbox sandbox, RestClient restClient)`）

| 方法 | 契约 |
| --- | --- |
| `@Tool(name="http_get", description="发起一个 HTTP GET 请求，返回响应体") String httpGet(@ToolParam("要请求的完整 URL") String url)` | enforce(HTTP_REQUEST, url) → `restClient.get().uri(url).retrieve().body(String.class)` |
| `@Tool(name="http_post", description="发起一个 HTTP POST 请求（JSON body），返回响应体") String httpPost(@ToolParam("要请求的完整 URL") String url, @ToolParam("JSON 请求体") String body)` | enforce(HTTP_REQUEST, url) → `post().uri(url).contentType(APPLICATION_JSON).body(body).retrieve().body(String.class)` |
| 非 2xx | **不特判、不自建错误页**：RestClient 默认抛含状态码异常 → 执行层收敛为失败结果（模型可自救）；与 notify"适配器上抛"并存——见 [data-model.md §2 失败口径表](../data-model.md) |

## InteractionTools（`InteractionTools(UserInteraction interaction)`）

```java
@Tool(name = "ask_user", description = "向当前用户提出一个问题并等待其回答（用于澄清需求或确认危险操作）")
public String askUser(@ToolParam(description = "要问用户的问题") String question) {
    return interaction.ask(question);   // 只管递问题、带回答回来；不吞异常、不设校验位
}
```

- 终端档 `ConsoleUserInteraction`：打印问题+读一行；EOF → `IllegalStateException`；IO → `UncheckedIOException`。
- 无人值守档 `UnsupportedUserInteraction`：`UnsupportedOperationException("当前渠道不支持向用户提问（无人值守渠道）")`——**绝不静默卡住**（US5.4/FR-009）。
- 零新对外异常类型（软门禁 #1）：不用 oryxos 的 `InteractionUnavailableException`。

## WebSearchTools（`WebSearchTools(SearchProvider provider)`）

```java
@Tool(name = "web_search", description = "用搜索引擎检索网页，返回标题、链接和摘要列表")
public String webSearch(@ToolParam(description = "搜索关键词") String query) { /* 渲染 provider.search(query) */ }
```

- 渲染为每条 `- 标题\n  链接\n  摘要`；空结果 → 提示文案（如"（未搜到相关结果）"）。
- **治理不在工具层**（工具无 Sandbox、零伪目标）：enforce 在**引擎入口**——`DuckDuckGoSearchProvider.search()` 第一行 `enforce(HTTP_REQUEST, <真实请求 URL>)`，通过才发请求、被拦则请求零发生（与 http_get 同口径被拦）。理由（D-08）：SableOS `ActionType` 四值中 HTTP_REQUEST 走域名白名单，伪目标会脏化 24 节解析——换引擎时新实现按 `SearchProvider` 契约同款在入口 enforce。
- 引擎：DDG Instant Answer（免 key，`format=json&no_html=1`）；解析 `AbstractText/AbstractURL/Heading` + `RelatedTopics[].Text/FirstURL` → `SearchResult(title,url,snippet)`；package-private 构造可注入 endpoint（测试指向本地 server）。

## Harness 映射（课件模板逐条保真）

| 测试类 | 用例 | 守点 |
| --- | --- | --- |
| `FileToolsTest` | `readFile_returnsFileContent` / `writeFile_writesContent` / `listDir_listsSortedNames`；`readFile_outsideWhitelistIsBlocked`（拒绝型 Sandbox + 断言文件未被碰）；`editFile_replacesUniqueOccurrence` / `editFile_oldStringNotFoundFailsWithoutWriting` / `editFile_multipleOccurrencesFailWithoutWriting`；`grep_returnsPathLineContent` / `grep_truncatesAtTwoHundredWithNote` / `grep_skipsBinaryAndNonUtf8Files`；`glob_matchesRecursivePattern` / `glob_truncatesAtTwoHundredWithNote`（`@TempDir` 夹具） | FR-002/FR-008、SC-002/SC-005 |
| `ShellToolsTest` | `shell_echoReturnsStdout`（OS 分支夹具）/ `shell_outsideWhitelistIsBlocked` / `shell_nonZeroExitCarriesStderr` / `shell_timeoutKillsProcess`（注入短超时 + OS 分支 sleep 夹具） | US2.2/3、SC-002 |
| `HttpToolsTest` | `httpGet_returnsResponseBody`（课件 `http_get_应能取回响应`）/ `httpGet_domainOutsideWhitelistIsBlocked`（课件 `http_get_命中白名单外域名应被拦下`，含假服务零请求断言）/ `httpPost_returnsResponseBody` / `httpGet_nonSuccessStatusCarriesStatusCode`（JDK `HttpServer` 假服务） | US2.3/4、SC-002 |
| `InteractionToolsTest` | `askUser_returnsConsoleAnswer`（注入流构造）/ `askUser_unattendedChannelFailsLoud`（`UnsupportedOperationException` + 消息含"不支持"） | US5.4、SC-005 |
| `WebSearchToolsTest` | `webSearch_rendersProviderResults`（假 provider）/ `webSearch_whitelistCheckedBeforeAnyRequest`（真 DDG 引擎 + 拒绝型 Sandbox + mock RestClient `verifyNoInteractions`）/ `duckDuckGoSearch_parsesInstantAnswerJson`（JDK `HttpServer` 夹具） | US5.5、SC-005 |

**测试方法名英文驼峰；课件中文原文（`http_get_应能取回响应` 等）进 `@DisplayName`。**
