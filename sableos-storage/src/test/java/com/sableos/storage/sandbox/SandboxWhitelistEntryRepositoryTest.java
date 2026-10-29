package com.sableos.storage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sableos.storage.StorageTestApplication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** V5 迁移与白名单两表的存取验收：表列齐、唯一索引拦截重复、标记表 CHECK 生效、entry_count=0 可写入。 */
@SpringBootTest(classes = StorageTestApplication.class)
class SandboxWhitelistEntryRepositoryTest {

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
      Path path = Files.createTempFile("sableos-whitelist-repo-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private SandboxWhitelistEntryRepository entryRepository;
  @Autowired private SandboxWhitelistSeedRepository seedRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTables() {
    entryRepository.deleteAll();
    seedRepository.deleteAll();
  }

  private static Set<String> columnNames(JdbcTemplate jdbc, String table) {
    List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(" + table + ")");
    return columns.stream().map(row -> String.valueOf(row.get("name"))).collect(Collectors.toSet());
  }

  @Test
  @DisplayName("V5 迁移建出两张表_条目表列齐")
  void v5Migration_createsEntriesTableWithAllColumns() {
    assertThat(columnNames(jdbcTemplate, "sandbox_whitelist_entries"))
        .contains("id", "type", "value", "description", "created_at", "updated_at");
  }

  @Test
  @DisplayName("V5 迁移建出播种标记表")
  void v5Migration_createsSeedTable() {
    assertThat(columnNames(jdbcTemplate, "sandbox_whitelist_seed"))
        .contains("id", "seeded_at", "entry_count");
  }

  @Test
  @DisplayName("唯一索引拦截同一 type 下的重复 value")
  void uniqueIndex_rejectsDuplicateTypeValue() {
    entryRepository.saveAndFlush(
        new SandboxWhitelistEntryEntity("HTTP_DOMAIN", "api.example.com", null));

    // 断言用 DataAccessException 而非更具体的 DataIntegrityViolationException：
    // SQLite 方言不把唯一约束翻译成后者，而是 JpaSystemException（实测），两者共同的父类才是可移植的断言目标。
    assertThatThrownBy(
            () ->
                entryRepository.saveAndFlush(
                    new SandboxWhitelistEntryEntity("HTTP_DOMAIN", "api.example.com", "dup")))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("UNIQUE constraint failed");
  }

  @Test
  @DisplayName("不同类型的相同 value 允许共存")
  void sameValueAcrossTypes_isAllowed() {
    entryRepository.saveAndFlush(new SandboxWhitelistEntryEntity("FILE_PATH", "x", null));
    entryRepository.saveAndFlush(new SandboxWhitelistEntryEntity("SHELL_COMMAND", "x", null));

    assertThat(entryRepository.count()).isEqualTo(2);
  }

  @Test
  @DisplayName("时间戳在插入时自动填充_更新时刷新 updated_at")
  void timestamps_filledOnPersistAndRefreshedOnUpdate() {
    SandboxWhitelistEntryEntity entity =
        entryRepository.saveAndFlush(new SandboxWhitelistEntryEntity("FILE_PATH", "a", null));
    var createdAt = entity.getCreatedAt();
    var firstUpdatedAt = entity.getUpdatedAt();

    assertThat(createdAt).isNotNull();
    assertThat(firstUpdatedAt).isNotNull();

    entity.setDescription("changed");
    SandboxWhitelistEntryEntity updated = entryRepository.saveAndFlush(entity);

    assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
    assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(firstUpdatedAt);
  }

  @Test
  @DisplayName("标记表只允许 id=1_写入其他 id 被 CHECK 拦截")
  void seedTable_rejectsNonSingletonId() {
    jdbcTemplate.update(
        "INSERT INTO sandbox_whitelist_seed (id, seeded_at, entry_count) VALUES (1, 0, 0)");

    // JdbcTemplate 不做方言翻译，CHECK 冲突冒泡为 UncategorizedSQLException；这里断言的是"被拒绝了"，
    // 故用 DataAccessException 父类 + 消息特征，兼顾未来换方言仍成立。
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO sandbox_whitelist_seed (id, seeded_at, entry_count) VALUES (2, 0, 0)"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("CHECK constraint failed");
  }

  @Test
  @DisplayName("entry_count 允许为 0_空配置首次导入也要落标记")
  void seedTable_allowsZeroEntryCount() {
    seedRepository.saveAndFlush(new SandboxWhitelistSeedEntity(0));

    assertThat(seedRepository.existsById(SandboxWhitelistSeedEntity.SINGLETON_ID)).isTrue();
    assertThat(seedRepository.findById(SandboxWhitelistSeedEntity.SINGLETON_ID))
        .get()
        .extracting(SandboxWhitelistSeedEntity::getEntryCount)
        .isEqualTo(0);
  }

  @Test
  @DisplayName("按类型与值查询命中_按类型排序稳定")
  void queries_byTypeAndValue_andOrdering() {
    entryRepository.saveAndFlush(
        new SandboxWhitelistEntryEntity("HTTP_DOMAIN", "b.example.com", null));
    entryRepository.saveAndFlush(
        new SandboxWhitelistEntryEntity("HTTP_DOMAIN", "a.example.com", null));
    entryRepository.saveAndFlush(new SandboxWhitelistEntryEntity("FILE_PATH", "z", null));

    assertThat(entryRepository.findByTypeAndValue("HTTP_DOMAIN", "a.example.com")).isPresent();
    assertThat(entryRepository.findByTypeAndValue("HTTP_DOMAIN", "missing")).isEmpty();

    assertThat(entryRepository.findAllByTypeOrderByValueAsc("HTTP_DOMAIN"))
        .extracting(SandboxWhitelistEntryEntity::getValue)
        .containsExactly("a.example.com", "b.example.com");

    assertThat(entryRepository.findAllByOrderByTypeAscValueAsc())
        .extracting(SandboxWhitelistEntryEntity::getType)
        .containsExactly("FILE_PATH", "HTTP_DOMAIN", "HTTP_DOMAIN");
  }
}
