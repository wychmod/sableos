# Quickstart: Agent Provider 验证向导

**Feature**: 001-lesson16-provider | 本文件只讲「怎么验」；结构口径见 [data-model.md](./data-model.md) 与 [contracts/](./contracts/)，实现细节见 tasks.md 与代码。

## 0. 前置

- JDK 21、Maven 3.9+（本机路径见开发环境约定）；仓库根 `D:\idea\sableos`。
- 无需任何 API key 即可完成场景 A–C（默认空 providers 清单）；场景 D 需要真实 `DEEPSEEK_API_KEY`。
- 首次改动后先 `mvn -q spotless:apply`（格式门禁绑 validate，不跑会挂构建）。

## 1. 场景 A：单测全绿（无需网络，默认必跑）

```bash
mvn -q -B test
```

期望：全绿。其中本节新增断言包含——

- `AgentLoaderTest`：多 Agent 加载/全字段解析/坏文件不阻断/provider 未登记点名报错；
- `ProviderServiceTest`：**三个关键回归测试**（双 provider 按名路由不串台；失败必落 `success=false` 审计且异常上抛；带工具 schema 调用时请求内自动执行已关闭）＋未知名抛 `ProviderNotFoundException`；
- `ToolSchemaAdapterTest`：schema 翻译字段一一对齐、`call()` 显式抛错（只翻译不执行）；
- `ProvidersPropertiesTest`：违规配置启动期点名报错（名字重复/空白、key 空白或 `${` 残留、openai 型缺 base-url）；
- `LlmCallRepositoryTest`：真 Flyway 迁移建的 `llm_calls` 能存能读，`success`/`error_message` 列真实存在。

## 2. 场景 B：零 key 装配可启动

```bash
mvn -pl sableos-boot -am test -Dtest=ProviderAssemblyTest
```

期望：Spring 上下文在**空 providers 清单**下正常升起；ProviderService/工厂/审计 bean 齐备；ProfileRegistry 为空；以未登记 provider 调用抛点名异常。

## 3. 场景 C：本地起服务（零 key）

```bash
mvn -q -DskipTests package
java -jar sableos-boot/target/sableos-boot-*.jar
```

期望：应用启动成功（DashScope 自动装配已被 `spring.ai.dashscope.enabled: false` 关闭，无缺 key 报错）；`curl localhost:8080/actuator/health` 返回 `UP`；SQLite 文件 `.sableos/sableos.db` 生成且含 `llm_calls` 表（Flyway 迁移成功）。

## 4. 场景 D：集成冒烟（真连通，打 `@Tag("integration")`，CI 默认跳过）

```bash
DEEPSEEK_API_KEY=sk-xxxx mvn -pl sableos-boot -am test -Dgroups=integration -Dtest=ProviderSmokeIT
```

期望（`ProviderSmokeIT` 的两个断言）：

1. 真调一次 deepseek 返回**非空文本**；
2. `llm_calls` 新增一条 `success=true` 记录（provider=deepseek、token/耗时非空）。

无 key 时该测试以 Assumptions 跳过（不算失败）。不传 `-Dgroups=integration` 时集成测试不会随日常 `mvn test` 触发。

## 5. 场景 E：课件「做完怎么验」手工项对照

| 手工项（课件五） | 本节对应验证 |
| --- | --- |
| 依赖能下载、能解析（坑三） | 场景 A 全绿即隐含成立；进一步 `mvn -pl sableos-provider dependency:tree` 可见 `spring-ai-openai:1.1.8` 与 `spring-ai-alibaba-starter-dashscope:1.1.2.4-security-fix` |
| 集成冒烟真跑过一次 | 场景 D |
| key 不落明文 | `grep -rn "sk-" --include="*.java" --include="*.yaml" --include="*.sql" .` 零命中 |
| 双 provider 路由 / 错误名报错 / 成败审计 / 自动执行关闭 / 只翻译不执行 | 已由场景 A 的单测覆盖（`mvn test` 绿 = 打勾） |

## 6. 场景 F：门禁全量（提交前）

```bash
mvn clean verify
```

期望：Spotless / Checkstyle / P3C(Pmd) / SpotBugs(+FindSecBugs) / 全部测试 全绿（OWASP depcheck 本地默认跳过，CI 强制跑）。这是「本节完成」的硬门禁定义。

## 7. 留证提示

- 场景 D 通过后可用任意 SQLite 工具查证审计行：`SELECT provider, model, success, trace_id, duration_ms FROM llm_calls ORDER BY id DESC LIMIT 3;`——`trace_id` 在 CLI/定时入口（18/25 节）接入前可能为 null，属预期。
- 最终验收报告将附：`mvn clean verify` 输出、六个测试类清单、交付物存在性核对、H4 不变量自查与剩余人工项清单。
