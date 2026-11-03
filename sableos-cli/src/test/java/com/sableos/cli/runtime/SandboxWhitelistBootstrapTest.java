package com.sableos.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sableos.core.sandbox.SandboxWhitelistStore;
import com.sableos.core.sandbox.WhitelistEntry;
import com.sableos.core.sandbox.WhitelistType;
import com.sableos.core.sandbox.WhitelistValidationException;
import com.sableos.tool.sandbox.FileSandboxProperties;
import com.sableos.tool.sandbox.HttpSandboxProperties;
import com.sableos.tool.sandbox.ShellSandboxProperties;
import com.sableos.tool.sandbox.WhitelistSandbox;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 白名单首次导入的验收：这是安全边界的入口，非法配置必须让启动失败，而不是静默跳过。
 *
 * <p>此前 {@code sableos-cli} 的 14 个类只有 1 个测试，本类是该模块唯一直接触碰安全逻辑的地方。
 */
class SandboxWhitelistBootstrapTest {

  private static final String ADMIN_PROPERTY = "sableos.sandbox.admin.enabled";

  @Test
  @DisplayName("首次启动_按配置播种并构造沙箱")
  void firstRun_seedsFromConfiguration() {
    FakeStore store = new FakeStore();
    MockEnvironment environment = new MockEnvironment();

    WhitelistSandbox sandbox =
        SandboxWhitelistBootstrap.buildSandbox(
            store,
            new FileSandboxProperties(List.of("/tmp/allowed")),
            new ShellSandboxProperties(List.of("ls")),
            new HttpSandboxProperties(List.of("example.com")),
            environment);

    assertThat(store.seeded).isTrue();
    assertThat(store.entries).hasSize(3);
    assertThat(sandbox).isNotNull();
  }

  @Test
  @DisplayName("已播种过_忽略配置文件且不重复插入")
  void alreadySeeded_ignoresConfiguration() {
    FakeStore store = new FakeStore();
    store.seeded.set(true);
    MockEnvironment environment = new MockEnvironment();

    SandboxWhitelistBootstrap.buildSandbox(
        store,
        new FileSandboxProperties(List.of("/tmp/allowed")),
        new ShellSandboxProperties(List.of("ls")),
        new HttpSandboxProperties(List.of("example.com")),
        environment);

    assertThat(store.seedCalls.get()).as("已播种则不应再调 seedIfNeeded").isZero();
    assertThat(store.entries).as("不应重新插入配置条目").isEmpty();
  }

  @Test
  @DisplayName("配置含非法域名_启动失败而不是静默跳过")
  void invalidDomain_failsStartup() {
    FakeStore store = new FakeStore();

    assertThatThrownBy(
            () ->
                SandboxWhitelistBootstrap.buildSandbox(
                    store,
                    new FileSandboxProperties(List.of()),
                    new ShellSandboxProperties(List.of()),
                    new HttpSandboxProperties(List.of("http://example.com/path")),
                    new MockEnvironment()))
        .isInstanceOf(WhitelistValidationException.class);

    assertThat(store.seeded).as("严格模式下不得留下半套播种结果").isFalse();
  }

  @Test
  @DisplayName("配置含空白与 null 条目_跳过而非失败")
  void blankAndNullEntries_areSkipped() {
    FakeStore store = new FakeStore();
    List<String> withBlanks = new ArrayList<>();
    withBlanks.add(null);
    withBlanks.add("   ");
    withBlanks.add("/tmp/allowed");

    SandboxWhitelistBootstrap.buildSandbox(
        store,
        new FileSandboxProperties(withBlanks),
        new ShellSandboxProperties(List.of()),
        new HttpSandboxProperties(List.of()),
        new MockEnvironment());

    assertThat(store.entries).as("只有非空白条目入库").hasSize(1);
    assertThat(store.entries.get(0).type()).isEqualTo(WhitelistType.FILE_PATH);
  }

  @Test
  @DisplayName("配置文件为空_播种空集但仍打标记")
  void emptyConfiguration_marksSeeded() {
    FakeStore store = new FakeStore();

    SandboxWhitelistBootstrap.buildSandbox(
        store,
        new FileSandboxProperties(List.of()),
        new ShellSandboxProperties(List.of()),
        new HttpSandboxProperties(List.of()),
        new MockEnvironment());

    assertThat(store.entries).isEmpty();
    assertThat(store.seeded).as("空配置也要打标记，否则管理员删光条目后重启会把配置再插一遍").isTrue();
  }

  @Test
  @DisplayName("admin 开关_缺省为开启")
  void adminEnabled_defaultsToTrue() {
    assertThat(SandboxWhitelistBootstrap.isAdminEnabled(new MockEnvironment()))
        .as("与 @ConditionalOnProperty(matchIfMissing = true) 同口径")
        .isTrue();
  }

  @Test
  @DisplayName("admin 开关_显式 false 关闭且大小写不敏感")
  void adminEnabled_respectsExplicitValue() {
    assertThat(
            SandboxWhitelistBootstrap.isAdminEnabled(
                new MockEnvironment().withProperty(ADMIN_PROPERTY, "FALSE")))
        .isFalse();
    assertThat(
            SandboxWhitelistBootstrap.isAdminEnabled(
                new MockEnvironment().withProperty(ADMIN_PROPERTY, "true")))
        .isTrue();
    assertThat(
            SandboxWhitelistBootstrap.isAdminEnabled(
                new MockEnvironment().withProperty(ADMIN_PROPERTY, "  ")))
        .isFalse();
  }

  /** 最小存储替身：只实现本类用到的语义，记录调用以供断言。 */
  private static final class FakeStore implements SandboxWhitelistStore {

    private final AtomicBoolean seeded = new AtomicBoolean(false);
    private final AtomicInteger seedCalls = new AtomicInteger();
    private final List<WhitelistEntry> entries = new ArrayList<>();

    @Override
    public List<WhitelistEntry> findAll(WhitelistType type) {
      return entries.stream().filter(e -> type == null || e.type() == type).toList();
    }

    @Override
    public Optional<WhitelistEntry> findById(long id) {
      return entries.stream().filter(e -> e.id() == id).findFirst();
    }

    @Override
    public Optional<WhitelistEntry> findByTypeAndValue(WhitelistType type, String normalizedValue) {
      return entries.stream()
          .filter(e -> e.type() == type && e.value().equals(normalizedValue))
          .findFirst();
    }

    @Override
    public WhitelistEntry insert(WhitelistType type, String normalizedValue, String description) {
      throw new UnsupportedOperationException("本测试不走逐条插入路径");
    }

    @Override
    public WhitelistEntry update(long id, String normalizedValue, String description) {
      throw new UnsupportedOperationException("本测试不覆盖更新路径");
    }

    @Override
    public void delete(long id) {
      throw new UnsupportedOperationException("本测试不覆盖删除路径");
    }

    @Override
    public boolean isSeeded() {
      return seeded.get();
    }

    @Override
    public int seedIfNeeded(List<SeedEntry> newEntries) {
      seedCalls.incrementAndGet();
      if (seeded.get()) {
        return 0;
      }
      int id = 1;
      for (SeedEntry entry : newEntries) {
        Instant now = Instant.now();
        entries.add(
            new WhitelistEntry(
                id++, entry.type(), entry.normalizedValue(), entry.description(), now, now));
      }
      seeded.set(true);
      return newEntries.size();
    }

    @Override
    public void markSeeded(int entryCount) {
      seeded.set(true);
    }

    @Override
    public long countByType(WhitelistType type) {
      return entries.stream().filter(e -> e.type() == type).count();
    }
  }
}
