# Contract: 程序入口与重命令启动（cli 聚合入口 + SableOsRuntime，对齐 oryxos）

> research D-06（参照 `D:\idea\oryxos` 成熟落地，2026-10-15 核实）。目标：轻命令零上下文秒回、重命令自启动完整装配、模块依赖方向不变（boot→cli，无环）、不新增第三方依赖。
> **原"boot 侧 SableOsLauncher + LazySpringFactory(IFactory) + SpringBackedCommand 标记"草案作废**——oryxos 证明"重命令在 run() 内自启动"更简单直白。

## fat JAR 主类

`sableos-boot` 仍产出可执行 fat JAR（AGENTS.md「boot 产出 fat JAR」不变），但 `spring-boot-maven-plugin` 配 `<mainClass>com.sableos.cli.SableOsCli</mainClass>`（oryxos：boot pom `<mainClass>io.oryxos.cli.OryxOsCli</mainClass>`）。

`SableOsCli.main` 纯 Picocli、不含 Spring：

```java
public static void main(String[] args) {
  System.exit(new CommandLine(new SableOsCli()).execute(args));
}
```

## 装配根 SableOsRuntime（在 sableos-cli）

```java
@SpringBootApplication(scanBasePackages = "com.sableos")
@EnableJpaRepositories(basePackages = "com.sableos.storage")
@EntityScan(basePackages = "com.sableos.storage")
public class SableOsRuntime {
  @Bean SessionManager sessionManager(SessionRepository repo) { return new JpaSessionManager(repo); }
  @Bean CliChannel cliChannel(AgentService agentService, SessionManager sessionManager) {
    return new CliChannel(agentService, sessionManager);
  }
}
```

- **JPA 扫描注解只此一处**（`SableOsRuntime`）：boot 的 `SableOsApplication` 剥掉 `@EntityScan`/`@EnableJpaRepositories`，降为最小 `@SpringBootApplication(scanBasePackages="com.sableos")`（vestigial，对齐 oryxos `OryxOsApplication`），避免两个应用类重复注册 JPA 仓库。
- boot 既有 `@Configuration`（`ReactConfiguration`/`ProviderConfiguration`/`AgentConfiguration`/`ObservabilityConfig`）**原地不动**，由 `SableOsRuntime` 的 `scanBasePackages="com.sableos"` 在运行期（fat JAR 含 boot 类）与 boot 测试期（boot→cli）扫到并装配。
- `ReactConfiguration` 去掉 `sessionStore()`；其 `agentService(ProfileRegistry, ReActLoop, SessionStore)` 注入的 `SessionStore` bean 即 `SableOsRuntime.sessionManager()`（`SessionManager extends SessionStore`，见 research D-10）。

## 重命令自启动（chat/serve/gateway）

```java
// ChatCommand.run()  —— oryxos ChatCommand 逐字同构
try (ConfigurableApplicationContext ctx =
    new SpringApplicationBuilder(SableOsRuntime.class)
        .web(WebApplicationType.NONE)      // chat 不占 HTTP 端口；serve 用 SERVLET
        .bannerMode(Banner.Mode.OFF)        // 对话界面干净
        .run()) {
  ctx.getBean(CliChannel.class).run(profileName, currentUser());
}
```

- `currentUser() = System.getProperty("user.name", "unknown")`（核心阶段无认证体系，取系统用户名；**无 `--user` 选项**，对齐 oryxos）。
- `serve` 用 `WebApplicationType.SERVLET`（起 Web 上下文；完整 REST 归 26 节，本节外壳）；`gateway` 同 serve 起上下文后占位。
- `serve`/`gateway` 起完上下文后必须 `keepAlive()`（`new CountDownLatch(1).await()`，对齐 oryxos）——`SableOsCli.main` 末尾是 `System.exit(execute(args))`，`run()` 一返回整机即退（实测启动完成后约 50ms 即 `GracefulShutdown`）；不阻塞则命令无法常驻。Ctrl-C 由 Spring 关闭钩子优雅收场。两者共用 `server.port`（默认 8080），同时运行会争端口。
- try-with-resources 保证一次性重命令跑完关上下文、进程可退出（`chat` 用；`serve`/`gateway` **不用**，见上条）。

## 轻命令零 Spring

`init`/`status`/`profile *`/`provider list`/`tool list`/`session list` 的 `run()` 直接文件/属性/裸 JDBC，**不构造 `SpringApplicationBuilder`**（不触发上下文启动，秒回）。`session list` 经 `LightDbConfig` 裸 JDBC（见 cli-commands.md）。

## 依赖方向（无环）

```
core ← storage ← boot
core ← channel-cli ← cli ← boot
cli → {core, storage, channel-cli}（聚合；不依赖 boot）
```

- `sableos-cli` 新增依赖：`sableos-storage`、`sableos-channel-cli`、`spring-boot-starter-data-jpa`、`sqlite-jdbc`、`snakeyaml`（+ 既有 core/picocli/spring-boot-starter）。均为 BOM 内既有构件，非新第三方。
- `SableOsRuntime`（cli）编译期只引用 core（`AgentService`/`SessionManager`）、storage（`SessionRepository`/`JpaSessionManager`）、channel-cli（`CliChannel`）类型；其余 bean（Provider/ReAct/Profile 等）运行期由组件扫描从 boot 的 @Configuration 收编，cli 无需编译依赖 boot/provider/memory/tool。

## 扫描范围声明（FR-012 / 课件"真实踩过的坑"）

`@EntityScan("com.sableos.storage")` + `@EnableJpaRepositories("com.sableos.storage")` 在 `SableOsRuntime`（唯一一处）。重命令经 `SpringApplicationBuilder(SableOsRuntime.class)` 启动即带上这两声明 → "Found N JPA repository interfaces" N>0（含 `SessionRepository`/`LlmCallRepository`/`ToolInvocationRepository`），审计与会话可写。**不得**只靠 `scanBasePackages`（它不带动 JPA 仓库/实体扫描）。

## 验收（人工为主，进程级）

- 轻命令（`sableos profile list`/`init`/`session list`）秒回、日志无 Spring banner（未起上下文）。
- 重命令（`sableos chat`）启动日志出现 Spring banner（bannerMode OFF 时无 banner，但仍有上下文启动日志）且 "Found N JPA repository interfaces" N>0。
- boot 的 `@SpringBootTest`（`ReactAssemblyTest`/`ProviderAssemblyTest`）改指 `classes=SableOsRuntime.class`，验证装配根迁移后 16/17 节 bean 仍齐、审计仍落库。
