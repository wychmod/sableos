# Contract: ToolRegistry + AnnotatedToolAdapter（统一注册表与注解管道）

**来源**: 课件 §二/§三（"先定统一抽象、注册进 ToolRegistry，循环只跟 SableTool 打交道" / "三条注册路径、按 Profile 的 tools 过滤"）+ FR-001/FR-004/FR-006/FR-007。

## 注册表接口契约

```java
// com.sableos.tool.ToolRegistry implements com.sableos.core.tool.ToolCatalog
public void register(SableTool tool);            // 直接实现来源；重名 → IllegalStateException("工具重名，拒绝注册: " + name)
public void registerAnnotated(Object bean);      // @Tool 扫描：ToolCallbacks.from(bean) → AnnotatedToolAdapter → register
public boolean contains(String name);
public List<SableTool> all();                    // 只读快照（契约测试 MethodSource）
public List<SableTool> filterByNames(List<String> names);
// 接口继承：List<SableTool> toolsFor(Profile profile); SableTool find(String name);   // find 未命中 → null
```

## 行为契约

| # | 契约 | 守点 |
| --- | --- | --- |
| C1 | 三种来源（直接实现 / `@Tool` 注解 / MCP 适配器）都以 `SableTool` 身份在同一注册表可查 | 循环/执行器对来源零感知（FR-001） |
| C2 | 重名二次注册：抛 `IllegalStateException`、消息点名工具名；**不静默覆盖** | 两个来源打架可查（US1.4） |
| C3 | `filterByNames` 结果与"声明列表中存在于注册表的项"**精确相等**：多一（没过滤干净）少一（过滤过头）都错；未知名跳过不报错；顺序随声明 | "不多不少"断言（US1.3/SC-004） |
| C4 | `toolsFor(profile)` = `filterByNames(profile.tools())`——**空声明得空结果**（最小权限） | US1.3/Edge Cases |
| C5 | `find` 未命中返回 `null`（core 契约）；`ToolExecutor` 据此产出"未注册的工具：X"失败 | 17 节既有路径 |
| C6 | `AnnotatedToolAdapter.execute`：`callback.call(input == null ? "{}" : input.toString())` → `ToolResult.success(output)`；**异常不捕获**（管道不吞，执行层统一收敛） | US4.2 / FR-004 |
| C7 | 装配后 `ToolCatalog` bean = `ToolRegistry`（boot `ToolConfiguration` 提供；`ReactConfiguration.EmptyToolCatalog` 删除） | FR-007：Prompt 组装与执行器取到的同一份 |
| C8 | 每个注册工具满足契约三件套（name/description/inputSchema 非空）——参数化遍历自动纳新 | `SableToolContractTest`（SC-001） |

## 方法级行为示例（mock 语义）

- `registerAnnotated(bean)`：bean 内**每个** `@Tool` 方法产生一个回调，逐一包装注册；bean 无注解方法 → 零注册不报错。
- `@Tool` 方法名与工具名不同时以 `@Tool(name=...)` 为准（工具名即注册键）。
- 参数 schema 由 Spring AI 从方法签名生成（`@ToolParam(description=...)` 进 schema 的属性描述）——`AnnotatedToolAdapter` 只搬运、不自造 schema。

## Harness 映射

| 测试 | 断言 |
| --- | --- |
| `ToolRegistryTest.allThreeSourcesRegisterAsSableTool` | 直接实现替身 + `@Tool` 注解 bean + mock MCP 适配器三件都 `contains`、`all().size()==3`（课件"C1"） |
| `ToolRegistryTest.duplicateNameIsRejected` | `assertThrows(IllegalStateException)` 且消息含工具名（C2） |
| `ToolRegistryTest.filterByProfileToolsIsExact` | 声明 `[read_file, http_get, not_registered]` → 结果恰 `[read_file, http_get]`（C3） |
| `SableToolContractTest.everyRegisteredToolDeclaresContractTriplet` | `@ParameterizedTest @MethodSource("allRegisteredTools")`：name/description/inputSchema 全非空（C8；课件模板逐行落地，方法名译英文、`@DisplayName` 保留课件原文） |
| `ReactAssemblyTest`（boot） | 装配后 `toolCatalog.find("http_get")` 非 null、`toolsFor(空 profile)` 为空（C7） |

**MethodSource 口径**：`allRegisteredTools` 按**运行时装配同构**构造（内置五 bean + NotifyTools 空表 + mock 依赖全注册后 `registry.all().stream()`）——新工具接入自动纳入契约检查（课件"新工具自动纳入"）。
