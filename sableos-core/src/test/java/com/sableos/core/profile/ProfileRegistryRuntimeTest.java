package com.sableos.core.profile;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sableos.core.agent.AgentLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * 课件《第29节》验收 harness：ProfileRegistryRuntimeTest——运行期 register/remove/exists 可用，
 * 且非法配置在运行时入口与启动扫描上抛出同一异常类型与同一消息。
 */
class ProfileRegistryRuntimeTest {

  private static final Set<String> KNOWN_PROVIDERS = Set.of("deepseek");

  @TempDir Path agentsDir;

  private static Profile profile(String name) {
    return new Profile(
        name,
        "测试 Agent",
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(null, null));
  }

  private Path writeAgent(String dirName, String markdown) throws IOException {
    Path dir = Files.createDirectories(agentsDir.resolve(dirName));
    Files.writeString(dir.resolve("AGENT.md"), markdown, StandardCharsets.UTF_8);
    return dir;
  }

  @Test
  @DisplayName("register 后 get 立即可见，all 快照含之")
  void register_thenGetSeesItImmediately() {
    ProfileRegistry registry = new ProfileRegistry();

    registry.register(profile("runtime-agent"));

    assertThat(registry.get("runtime-agent")).isNotNull();
    assertThat(registry.get("runtime-agent").name()).isEqualTo("runtime-agent");
    assertThat(registry.exists("runtime-agent")).isTrue();
    assertThat(registry.all()).hasSize(1);
  }

  @Test
  @DisplayName("remove 命中返回 true、再查为 null、重复 remove 返回 false")
  void remove_reportsWhetherItRemovedSomething() {
    ProfileRegistry registry = new ProfileRegistry();
    registry.register(profile("gone"));

    assertThat(registry.remove("gone")).isTrue();
    assertThat(registry.get("gone")).isNull();
    assertThat(registry.exists("gone")).isFalse();
    assertThat(registry.remove("gone")).as("重复移除应返回 false").isFalse();
    assertThat(registry.remove("never-existed")).isFalse();
  }

  @Test
  @DisplayName("同名 register 覆盖旧实例，不产生两条")
  void register_sameName_overwrites() {
    ProfileRegistry registry = new ProfileRegistry();
    registry.register(profile("dup"));
    registry.register(profile("dup"));

    assertThat(registry.all()).hasSize(1);
  }

  @Test
  @DisplayName("非法配置：运行时入口与启动扫描抛同一异常类型与同一消息")
  void invalidConfig_runtimeAndStartupThrowIdentical() throws IOException {
    Path bad = writeAgent("bad-agent", "---\nname: other-name\n---\n正文\n");
    AgentLoader loader = new AgentLoader(agentsDir, KNOWN_PROVIDERS);

    // 运行时入口：直接派生，异常原样上抛
    Throwable fromRuntime = catchThrowable(() -> loader.deriveProfile(bad));
    assertThat(fromRuntime).isInstanceOf(IllegalArgumentException.class);

    // 启动扫描：内部走同一条派生路径，但对外只跳过；用 ListAppender 抓它记下的原因
    String scannedReason = firstErrorMentioning("bad-agent", loader::loadAll);

    assertThat(fromRuntime.getMessage()).contains("other-name").contains("bad-agent");
    assertThat(scannedReason).as("启动扫描记录的原因应与运行时入口抛出的消息一致").contains(fromRuntime.getMessage());
  }

  @Test
  @DisplayName("扫描得到的注册表可继续被运行时增删，两者共用同一份索引")
  void scannedRegistry_supportsRuntimeMutations() throws IOException {
    writeAgent(
        "scanned",
        "---\nname: scanned\nprovider:\n  name: deepseek\n  model: deepseek-chat\n---\n正文\n");
    ProfileRegistry registry = new AgentLoader(agentsDir, KNOWN_PROVIDERS).loadAll();
    assertThat(registry.exists("scanned")).isTrue();

    registry.register(profile("added-later"));

    assertThat(registry.all()).hasSize(2);
    assertThat(registry.remove("scanned")).isTrue();
    assertThat(registry.all()).hasSize(1);
    assertThat(registry.exists("added-later")).isTrue();
  }

  private static Throwable catchThrowable(ThrowingCallable callable) {
    try {
      callable.call();
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  /** 抓 AgentLoader 记下的第一条 ERROR 文案（含指定目录名）。 */
  private static String firstErrorMentioning(String needle, Runnable action) {
    Logger logger = (Logger) LoggerFactory.getLogger(AgentLoader.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      action.run();
    } finally {
      logger.detachAppender(appender);
    }
    return appender.list.stream()
        .filter(event -> event.getLevel() == Level.ERROR)
        .map(ILoggingEvent::getFormattedMessage)
        .filter(message -> message.contains(needle))
        .findFirst()
        .orElseThrow(() -> new AssertionError("未找到点名 " + needle + " 的错误日志"));
  }

  @FunctionalInterface
  private interface ThrowingCallable {
    void call() throws Exception;
  }
}
