package com.sableos.storage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sableos.core.sandbox.WhitelistDuplicateException;
import com.sableos.core.sandbox.WhitelistEntry;
import com.sableos.core.sandbox.WhitelistEntryNotFoundException;
import com.sableos.core.sandbox.WhitelistType;
import com.sableos.storage.StorageTestApplication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** JpaSandboxWhitelistStore 的行为验收：DB 异常翻译、更新排除自身、删除不存在、标记读写。 */
@SpringBootTest(classes = StorageTestApplication.class)
class JpaSandboxWhitelistStoreTest {

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
      Path path = Files.createTempFile("sableos-whitelist-store-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private JpaSandboxWhitelistStore store;
  @Autowired private SandboxWhitelistEntryRepository entryRepository;
  @Autowired private SandboxWhitelistSeedRepository seedRepository;

  @BeforeEach
  void cleanTables() {
    entryRepository.deleteAll();
    seedRepository.deleteAll();
  }

  @Test
  @DisplayName("插入后可按类型与值查到_且值保持归一后形态")
  void insert_thenFindByTypeAndValue() {
    WhitelistEntry entry = store.insert(WhitelistType.HTTP_DOMAIN, "api.example.com", "demo");

    assertThat(entry.id()).isPositive();
    assertThat(entry.value()).isEqualTo("api.example.com");
    assertThat(store.findByTypeAndValue(WhitelistType.HTTP_DOMAIN, "api.example.com")).isPresent();
  }

  @Test
  @DisplayName("重复插入被翻译成领域异常_而非泄漏数据库异常")
  void insert_duplicate_isTranslatedToDomainException() {
    store.insert(WhitelistType.HTTP_DOMAIN, "api.example.com", null);

    assertThatThrownBy(() -> store.insert(WhitelistType.HTTP_DOMAIN, "api.example.com", "dup"))
        .isInstanceOf(WhitelistDuplicateException.class)
        .hasMessageContaining("api.example.com");
  }

  @Test
  @DisplayName("更新为新值成功_旧值不再可查")
  void update_changesValue() {
    WhitelistEntry created = store.insert(WhitelistType.FILE_PATH, ".sableos/agents", null);

    WhitelistEntry updated = store.update(created.id(), ".sableos/skills", "改值");

    assertThat(updated.value()).isEqualTo(".sableos/skills");
    assertThat(store.findByTypeAndValue(WhitelistType.FILE_PATH, ".sableos/agents")).isEmpty();
    assertThat(store.findByTypeAndValue(WhitelistType.FILE_PATH, ".sableos/skills")).isPresent();
  }

  @Test
  @DisplayName("更新成自己的原值不算重复")
  void update_sameValueOnSelf_isAllowed() {
    WhitelistEntry created = store.insert(WhitelistType.FILE_PATH, ".sableos/agents", null);

    WhitelistEntry updated = store.update(created.id(), ".sableos/agents", "只改说明");

    assertThat(updated.description()).isEqualTo("只改说明");
    assertThat(updated.value()).isEqualTo(".sableos/agents");
  }

  @Test
  @DisplayName("更新撞到同类型的另一条_抛冲突且原值不变")
  void update_collidingWithAnother_throwsAndKeepsOriginal() {
    WhitelistEntry first = store.insert(WhitelistType.FILE_PATH, ".sableos/a", null);
    store.insert(WhitelistType.FILE_PATH, ".sableos/b", null);

    assertThatThrownBy(() -> store.update(first.id(), ".sableos/b", null))
        .isInstanceOf(WhitelistDuplicateException.class);

    assertThat(store.findById(first.id()))
        .get()
        .extracting(WhitelistEntry::value)
        .isEqualTo(".sableos/a");
  }

  @Test
  @DisplayName("更新不存在的 id 抛未找到")
  void update_missingId_throwsNotFound() {
    assertThatThrownBy(() -> store.update(9999L, "x", null))
        .isInstanceOf(WhitelistEntryNotFoundException.class);
  }

  @Test
  @DisplayName("删除后不可查_删除不存在抛未找到")
  void delete_removesAndRejectsMissing() {
    WhitelistEntry created = store.insert(WhitelistType.SHELL_COMMAND, "python", null);

    store.delete(created.id());
    assertThat(store.findById(created.id())).isEmpty();
    assertThatThrownBy(() -> store.delete(created.id()))
        .isInstanceOf(WhitelistEntryNotFoundException.class);
  }

  @Test
  @DisplayName("列表按 type 再按 value 排序_可按类型过滤")
  void findAll_ordersAndFilters() {
    store.insert(WhitelistType.HTTP_DOMAIN, "b.example.com", null);
    store.insert(WhitelistType.HTTP_DOMAIN, "a.example.com", null);
    store.insert(WhitelistType.FILE_PATH, "z", null);

    assertThat(store.findAll(WhitelistType.HTTP_DOMAIN))
        .extracting(WhitelistEntry::value)
        .containsExactly("a.example.com", "b.example.com");

    assertThat(store.findAll(null))
        .extracting(WhitelistEntry::type)
        .containsExactly(
            WhitelistType.FILE_PATH, WhitelistType.HTTP_DOMAIN, WhitelistType.HTTP_DOMAIN);
  }

  @Test
  @DisplayName("标记读写：未标记→已标记，entry_count 可为 0")
  void seedFlag_roundTrip() {
    assertThat(store.isSeeded()).isFalse();

    store.markSeeded(0);

    assertThat(store.isSeeded()).isTrue();
    assertThat(seedRepository.findById(SandboxWhitelistSeedEntity.SINGLETON_ID))
        .get()
        .extracting(SandboxWhitelistSeedEntity::getEntryCount)
        .isEqualTo(0);
  }

  @Test
  @DisplayName("按类型计数")
  void countByType_countsOnlyThatType() {
    store.insert(WhitelistType.FILE_PATH, "a", null);
    store.insert(WhitelistType.FILE_PATH, "b", null);
    store.insert(WhitelistType.SHELL_COMMAND, "c", null);

    assertThat(store.countByType(WhitelistType.FILE_PATH)).isEqualTo(2);
    assertThat(store.countByType(WhitelistType.SHELL_COMMAND)).isEqualTo(1);
    assertThat(store.countByType(WhitelistType.HTTP_DOMAIN)).isZero();
    assertThat(store.findAll(null)).hasSize(3);
  }

  @Test
  @DisplayName("空库列表返回空表而非 null")
  void findAll_emptyReturnsEmptyList() {
    List<WhitelistEntry> all = store.findAll(null);
    assertThat(all).isNotNull().isEmpty();
  }
}
