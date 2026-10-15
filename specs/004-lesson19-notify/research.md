# Phase 0 Research: Notify——出站通知（第 19 节）

> 决策记录。每条含 Decision / Rationale / Alternatives。附录 A 是第三方 API 的本地核实证据（软门禁 #5）。
> 本节**不新增任何第三方依赖**（软门禁 #6）——`spring-web` 属 Boot BOM 管理构件（`spring-framework.version` 6.2.19，已在本地仓库核实），非新第三方构件。

## D-01 通知抽象三件：签名逐字保真 + 防御拷贝 + 落位 `sableos-tool`

**Decision**: 三件落 `sableos-tool` 的 `com.sableos.tool.notify` 包，签名与课件逐字一致：

- `NotifyChannelAdapter`（接口）：`void send(NotifyTarget target, String content);`
- `NotifyTarget`（record）：`record NotifyTarget(String channelType, Map<String, String> config)`；**追加** compact 构造做防御拷贝（`config` 为 null → `Map.of()`，否则 `Map.copyOf(config)`），使 `config` 恒非 null 且不可变。
- `WebhookNotifyAdapter`（类）：`implements NotifyChannelAdapter`，构造注入 `RestClient`（与课件骨架逐字一致）；**纯 POJO 零 Spring 注解**。

**Rationale**: 课件是骨架字面量的权威来源（本 skill H1/H5：已定字面量逐字保真、只创建交付物点名的对外概念）；接口签名与 record 组件一字不改。防御拷贝是仓库既有 record 约定（core `ProviderResponse` 同款 compact 构造：`null → List.of()`、否则 `List.copyOf`），把"实现层拿到的配置不可能被外部改写"变成 record 自己的不变量，且缺 url 路径免 NPE（`config().get("url")` 恒可调）。纯 POJO 是 clarify 拍板（不加 `@Component`、不建配置类，装配归 20 节 ToolRegistry 统一收编，对标 oryxos D6 先例）。

**Alternatives considered**:
- 裸 record（课件原样、无 compact 构造）：`config()` 暴露调用方传入的原始 Map 引用，运行期可能被外部改写；且 core 的同型 record 都有防御拷贝，两处风格分裂。**否决**（compact 构造是签名零改的追加式不变量）。
- 加 `@Component` 让 Spring 直接扫到：违背装配归 20 节拍板；本节测试也就不再需要"零上下文构造"。**否决**。

## D-02 发送实现：RestClient 直发 + 缺 url 快速失败

**Decision**: `send(NotifyTarget, String)` 两步：

1. `String url = target.config().get("url");` —— `url == null || url.isBlank()` 即抛 `IllegalArgumentException`，消息含 `url` 字面（报错点名缺失的配置键），**零请求发出**。
2. `restClient.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(Map.of("content", content)).retrieve().toBodilessEntity();` —— 课件骨架逐字；`Map.of` 值恒非 null，body 恒为 `{"content":"<text>"}`。

**Rationale**: 课件骨架即最终形态（ORYXOS 终态仅多一层 `NotifyPoster`，那是它出网策略落地后的演进，本节口径按课件直发）。缺 url 快速失败 = FR-002 + 验收④；oryxos 实证（其 IAE 消息同样点名 `url`、测试断言 `message.contains("url")` 与零请求）。同步阻塞调用，符合「无 Reactor/CompletableFuture」不变量。

**Alternatives considered**:
- 不发请求、缺 url 时发空 body / 静默跳过：违「报错点名零请求」验收④。**否决**。
- 内部自建 RestClient（构造不注入）：测试无法指向假 webhook 随机端口（模块要硬编码地址）、由实现决定网络栈也违依赖注入惯例。**否决**。

**核实点（写前 H3）**: 已核实本地 `spring-web-6.2.19.jar` 的完整调用链存在（附录 A）：`RestClient.create()` / `post()` / `uri(String, Object...)` / `contentType(MediaType)` / `body(Object)` / `retrieve()` / `toBodilessEntity()`。

## D-03 失败口径：默认即抛；4xx/5xx 界定；3xx 顺延 23/24 节

**Decision**: **不注册任何自定义响应错误处理器**——依赖 RestClient 默认语义：4xx/5xx 抛 `RestClientResponseException`（及其子类），连接失败抛 `ResourceAccessException`，两者均 `extends RestClientException`。3xx 默认**不抛**（默认判定只认 hundreds ∈ {4,5}；JDK HttpClient 默认不自动跟随重定向）——本节不特判、不声称已管控；重定向出网管控（禁跟随、逐跳复检白名单、无 Location 明确报错）整体归 23/24 节 Sandbox 出网策略（clarify #2 拍板）。

**Rationale**: 已逐字节码核实默认判定口径（附录 A：`StatusHandler.createDefault` → `HttpStatusCode.isError()` → hundreds 去重 ∈ {4,5}）；"不注册吞错处理器"即「已判定的非成功必抛」的最稳实现——不自造逻辑、不引入未经验证的语义。3xx 管控与域名白名单同属一道出网安全墙，一起落 24 节才自洽（oryxos 先例：其 redirect 系列断言就是在 `NotifyPoster`+`WhitelistSandbox` 落地后才补的）。

**Alternatives considered**:
- 自定义 ErrorHandler 把 3xx 也判错：自造 Web 语义（3xx 是控制流而非失败）、且把出网安全策略提前泄漏进本节。**否决**。
- 本节就实现 redirect 管控（照搬 oryxos 终态 NotifyPoster）：越界——课件明说白名单与出网细节归 23/24 节，本节只留接线位。**否决**。

## D-04 假 webhook：JDK 内置 `HttpServer`（零新依赖）

**Decision**: `WebhookNotifyAdapterTest` 用 `com.sun.net.httpserver.HttpServer`（`new InetSocketAddress(0)` 随机端口）承接请求：记录 `(method, path, body)`、响应码可切（默认 200，测试内改 500）。四条断言（方法名英文，课件原文进 `@DisplayName`）：

| 测试方法（英文） | @DisplayName（课件原文） | 断言 |
| --- | --- | --- |
| `sendPostsJsonBodyContainingContent` | 发送后收到一次 POST，body 里带 content | 恰好一次请求；method=POST；body 含断言内容；body 含 `"content"`（按约定包成 `{"content": ...}`） |
| `urlComesFromTargetConfigNotHardcoded` | 换通知目标只改配置零代码 | 同一实现、两个不同 url 配置 → 假服务收到两条不同 path 的请求 |
| `serverErrorPropagatesAsException` | webhook 返回 5xx 异常向上抛、不静默吞掉 | 500 时抛 `RestClientResponseException` |
| `missingUrlFailsFastWithoutRequest` | 缺 url 配置：报错点名且零请求发出 | 抛 `IllegalArgumentException` 且 `message.contains("url")`；假服务零请求 |

**Rationale**: clarify 拍板 + oryxos 同节 research D5 先例（其单测全部采用此机制）。`jdk.httpserver` 是 JDK 标准模块，零新第三方依赖、不扩大 CI 依赖安全扫描面。5xx 断言用具体类型 `RestClientResponseException`（失败形态精确、不用宽泛的 Exception）。

**Alternatives considered**: MockWebServer（课件字面）——新增第三方测试依赖（触发软门禁 #6），且扩大 CI 依赖 CVE 扫描面。**否决**。自写 ServerSocket 假服务——手解析 HTTP 无谓复杂度。**否决**。

## D-05 依赖与装配：`sableos-tool` += `spring-web`；测试零 Spring 上下文

**Decision**: `sableos-tool/pom.xml` 新增 `org.springframework:spring-web`（**不带版本**，由父 POM Boot BOM 管理 → 6.2.19）。生产类零 Spring 注解；harness 直接 `new WebhookNotifyAdapter(RestClient.create())`，不起 Spring 上下文；测试框架仍用父 POM 自动注入的 `spring-boot-starter-test`（JUnit5/Mockito/AssertJ），`sableos-tool` pom 不加任何测试依赖。JSON body 序列化靠 `jackson-databind`（core 的编译依赖，传递可达 `sableos-tool`；`RestClient.create()` 的默认转换器按类路径探测 Jackson，附录 A 核实）。

**Rationale**: clarify 拍板（`spring-web` 是 Boot BOM 既有构件、非新第三方）；父 POM 已对所有模块注入 `spring-boot-starter-test`（test scope，父 POM 第 225~231 行）；纯 POJO + 零上下文 = 最快的 harness 与最干净的模块边界。装配（`WebhookNotifyAdapter` 变 bean、`ToolRegistry` 收编、`NotifyTools` 注册）归 20 节。

**Alternatives considered**: 引 `spring-boot-starter-web` 整包——只为一个 RestClient 拖进 tomcat/jackson starter 一大串。**否决**。本节自建 `@Configuration` 提供 `RestClient` bean——违「装配归 20 节」拍板。**否决**。

## D-06 本轮范围：三件 + 第一批 harness；`NotifyTools` 登记不落实现

**Decision**: 本轮实现 = 抽象三件 + `WebhookNotifyAdapterTest`（上表四条）+ `sableos-tool` pom 加 `spring-web`。`NotifyTools` 与其第二批 harness（渠道解析三断言 + `InOrder` enforce 先于 send）在 tasks.md 中**登记为待接线任务**（完成时点 24 节之后，27/28 节全量验证），本轮不实现。契约定死于 `contracts/notify-tool.md`（content 必传；channel 缺省/空白/`default` → 注册表第一个渠道；未注册/无渠道/上下文缺失 → 明确报错零请求；`Sandbox.enforce` 先于 `send`；审计走 `ToolExecutor` 既有路径），20/24 节接线时按此落地、按此补测试。

**Rationale**: 课件「实现顺序说明」明载（授课顺序 ≠ 构建顺序）+ clarify 拍板。契约先定死是「接口先行」设计习惯在本节的直接体现：注册表模型（按名引用）与 enforce 順序不依赖 20/24 节的实现细节，可以先写成验收标准。

**Alternatives considered**: 本节硬做 NotifyTools（mock 直调）——依赖 `@Tool` 注册（20 节）与 `Sandbox.enforce`（24 节），做了也接不了线、第二批 harness 照样跑不了，等于双份返工，且课件明确说完成时点在 24 节后。**否决**。

## D-07 静态门禁与测试写法预案

**Decision**:
- 测试方法名英文驼峰、课件中文原文进 `@DisplayName`（见 D-04 表）；文件头 javadoc 注明"课件《第19节》验收 harness 第一批"（利用既有先例格式）。
- 假 webhook 请求记录用 `CopyOnWriteArrayList`（handler 在 HttpServer 工作线程写、测试线程读，免除跨线程可见性噪音）。
- `WebhookNotifyAdapter` 的构造若被 SpotBugs 判 `EI_EXPOSE_REP2`（持有注入的协作者引用）——按 `PromptBuilder` 先例做**方法级定点抑制**并写 justification（RestClient 线程安全、注入共享引用是本类设计意图）；若未告警则不加。撤销条件写进 justification。
- 本类不打印任何 url/config 内容进日志（FR-005：地址与凭证不进日志）；无用户可控字符串入日志路径。
- 避开 Java 18+ 语法形态（增强 switch `default ->`、record pattern 等）。

**Rationale**: 门禁已核（父 POM：Spotless/Checkstyle/P3C/SpotBugs+FindSecBugs 全绑 `mvn verify`）；`EI_EXPOSE_REP2` 定点抑制是本仓既定模式（`PromptBuilder` 存 `ContextLoader`、`TraceIds` 两处，全仓仅 3 处、均方法级带 justification）。

**Alternatives considered**: 为避免告警改构造签名（如注入 `ClientHttpRequestFactory`）——改课件已定字面量（软门禁 #2 触发点），且真实告警应按先例抑制而非改设计。**否决**。

---

## 附录 A：`spring-web` 6.2.19 本地核实证据（软门禁 #5）

核实对象：本地仓库 `D:\maven\Repository\org\springframework\spring-web\6.2.19\spring-web-6.2.19.jar`（版本经父 POM `spring-framework.version` → Boot BOM 管理，节前已 `mvn help:evaluate` 核实）。方法：`javap -p -c` 反汇编 + 签名核对。

**A-1 调用链方法存在性（签名逐项）**：

| 调用 | 签名（核实） |
| --- | --- |
| `RestClient.create()` | `public static RestClient create()` |
| `restClient.post()` | `RequestBodyUriSpec post()` |
| `.uri(url)` | `RequestBodySpec uri(String, Object...)`（`DefaultRestClient$DefaultRequestBodyUriSpec` 实现） |
| `.contentType(MediaType)` | `RequestBodySpec contentType(MediaType)` |
| `.body(Object)` | `RequestBodySpec body(Object)` |
| `.retrieve()` | `ResponseSpec retrieve()` |
| `.toBodilessEntity()` | `ResponseEntity<Void> toBodilessEntity()` |
| 异常类型 | `RestClientResponseException extends RestClientException`；`ResourceAccessException extends RestClientException` |

**A-2 默认失败判定口径（逐字节码核实）**：`StatusHandler.createDefault` 的静态链 → `HttpStatusCode.isError()` → `DefaultHttpStatusCode.isError()` 逐字节码确认：按 HTTP status 的 `hundreds` 位判断，**仅 hundreds ∈ {4,5} 为 error**；3xx（hundreds=3）返回 false → 默认不抛、不静默吞的标准路径是「4xx/5xx 才抛」。JDK `java.net.http.HttpClient` 默认 `Redirect.NEVER`（`RestClient.create()` 无额外 request factory 配置时走 JDK 实现）→ 3xx 响应原样返回、不跟随。

**A-3 默认消息转换器（JSON body 序列化）**：`DefaultRestClientBuilder.initMessageConverters()`：当未显式配置转换器时，按类路径探测注册——`jackson2Present` 守卫下加入 `MappingJackson2HttpMessageConverter`。`sableos-tool` 类路径含 `jackson-databind`（经 `sableos-core` 传递）→ `.body(Map.of("content", content))` 以 `application/json` 序列化成功。

**A-4 `RestClient.create()` 可脱离 Spring 上下文构造**：`RestClient.create()` 为纯静态工厂（`DefaultRestClient.builder()` → 默认工厂经 `ClientHttpRequestFactories.get(...)` 探测：Apache → Jetty → **JDK HttpClient 兜底**）；无 Spring ApplicationContext 依赖 → 单测可 `new WebhookNotifyAdapter(RestClient.create())` 直连本地假 webhook。
