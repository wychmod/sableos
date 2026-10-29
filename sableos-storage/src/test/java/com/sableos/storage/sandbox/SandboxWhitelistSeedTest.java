package com.sableos.storage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sableos.core.sandbox.SandboxWhitelistStore;
import com.sableos.core.sandbox.WhitelistType;
import com.sableos.core.sandbox.WhitelistValidationException;
import com.sableos.storage.StorageTestApplication;
import com.sableos.tool.sandbox.WhitelistRules;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 首次导入的存储语义（specs/008 §5.3）：判据看标记表、导入与打标记同事务、空配置也落标记、非法值整体失败。
 *
 * <p>装配层（`sableos-cli` 的 SandboxWhitelistBootstrap）只是把配置归一后交给 {@link
 * SandboxWhitelistStore#seedIfNeeded}；原子性由存储层保证，故核心守点放在这里。
 */
@SpringBootTest(classes = StorageTestApplication.class)
class SandboxWhitelistSeedTest {

  private static final String DB_URL = "jdbc:sqlite:" + tempDbPath();

  @DynamicPropertySource
  static void overrideDatasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DB_URL);
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration/{vendor}");
    registry.add(
        "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type", () -> "TIMESTAMP");
  }

  private static String tempDbPath() {
    try {
      Path path = Files.createTempFile("sableos-whitelist-seedtest-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private SandboxWhitelistStore store;
  @Autowired private SandboxWhitelistEntryRepository entryRepository;
  @Autowired private SandboxWhitelistSeedRepository seedRepository;

  @BeforeEach
  void reset() {
    entryRepository.deleteAll();
    seedRepository.deleteAll();
  }

  private static List<SandboxWhitelistStore.SeedEntry> entries(
      WhitelistType type, String... values) {
    List<SandboxWhitelistStore.SeedEntry> list = new ArrayList<>();
    for (String value : values) {
      list.add(
          new SandboxWhitelistStore.SeedEntry(type, WhitelistRules.normalize(type, value), "seed"));
    }
    return list;
  }

  @Test
  @DisplayName("首次导入写入条目并打标记")
  void firstSeed_writesEntriesAndMark() {
    List<SandboxWhitelistStore.SeedEntry> all = new ArrayList<>();
    all.addAll(entries(WhitelistType.FILE_PATH, ".sableos/agents"));
    all.addAll(entries(WhitelistType.SHELL_COMMAND, "ls"));
    all.addAll(entries(WhitelistType.HTTP_DOMAIN, "api.example.com"));

    int inserted = store.seedIfNeeded(all);

    assertThat(inserted).isEqualTo(3);
    assertThat(store.isSeeded()).isTrue();
    assertThat(store.findAll(null)).hasSize(3);
  }

  @Test
  @DisplayName("已导入过则再调用不写任何条目")
  void secondSeed_isNoOp() {
    store.seedIfNeeded(entries(WhitelistType.SHELL_COMMAND, "python"));

    int inserted = store.seedIfNeeded(entries(WhitelistType.SHELL_COMMAND, "ls"));

    assertThat(inserted).isZero();
    assertThat(store.findAll(null)).extracting(e -> e.value()).containsExactly("python");
  }

  @Test
  @DisplayName("删光条目后再次导入仍不写_标记比条目表更权威")
  void afterDeletingAll_deletedEntriesDoNotRevive() {
    store.seedIfNeeded(entries(WhitelistType.SHELL_COMMAND, "python"));
    for (var entry : store.findAll(null)) {
      store.delete(entry.id());
    }

    int inserted = store.seedIfNeeded(entries(WhitelistType.SHELL_COMMAND, "python"));

    assertThat(inserted).isZero();
    assertThat(store.findAll(null)).isEmpty();
  }

  @Test
  @DisplayName("空配置也落标记_entry_count 为 0")
  void emptyEntries_stillMarksSeeded() {
    int inserted = store.seedIfNeeded(List.of());

    assertThat(inserted).isZero();
    assertThat(store.isSeeded()).isTrue();
    assertThat(seedRepository.findById(SandboxWhitelistSeedEntity.SINGLETON_ID))
        .get()
        .extracting(SandboxWhitelistSeedEntity::getEntryCount)
        .isEqualTo(0);
  }

  @Test
  @DisplayName("导入批次里的重复值只写一条")
  void duplicateValuesInBatch_writtenOnce() {
    List<SandboxWhitelistStore.SeedEntry> batch = new ArrayList<>();
    batch.addAll(entries(WhitelistType.FILE_PATH, ".sableos/agents", ".sableos/agents/"));

    int inserted = store.seedIfNeeded(batch);

    assertThat(inserted).isEqualTo(1);
    assertThat(store.findAll(WhitelistType.FILE_PATH)).hasSize(1);
  }

  @Test
  @DisplayName("非法配置值在归一时就抛出_不会进到导入")
  void invalidValue_failsBeforeSeeding() {
    assertThatThrownBy(() -> entries(WhitelistType.HTTP_DOMAIN, "中文.example.com"))
        .isInstanceOf(WhitelistValidationException.class);

    assertThat(store.isSeeded()).isFalse();
    assertThat(store.findAll(null)).isEmpty();
  }
}
