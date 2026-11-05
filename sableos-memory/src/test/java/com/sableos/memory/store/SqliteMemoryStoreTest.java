package com.sableos.memory.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sableos.memory.MemoryScope;
import com.sableos.memory.MemoryTestApplication;
import com.sableos.storage.memory.MemoryEntryRepository;
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

/** SQLite 后端专属测试：Flyway 建表、归档 LIMIT、LIKE 检索。 */
@SpringBootTest(classes = MemoryTestApplication.class)
class SqliteMemoryStoreTest {

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
      Path path = Files.createTempFile("sableos-memory-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private MemoryEntryRepository repository;

  private SqliteMemoryStore store;

  @BeforeEach
  void setUp() {
    store = new SqliteMemoryStore(repository);
  }

  @Test
  @DisplayName("写入后 load 能读到")
  void writeThenLoad() {
    store.append("归档内容", MemoryScope.ARCHIVAL, "p1");

    assertTrue(store.load("p1").contains("归档内容"));
  }

  @Test
  @DisplayName("归档 LIMIT 只保留最近 N 条")
  void archiveLimitKeepsRecent() {
    for (int i = 0; i < 150; i++) {
      store.append("流水 " + i, MemoryScope.ARCHIVAL, "p1");
    }
    store.append("核心", MemoryScope.CORE, "p1");

    String loaded = store.load("p1");

    assertTrue(loaded.contains("核心"));
    assertFalse(loaded.contains("流水 0"));
    assertTrue(loaded.contains("流水 149"));
  }

  @Test
  @DisplayName("recall 用 LIKE 只搜归档区")
  void recallSearchesOnlyArchive() {
    store.append("核心 apple", MemoryScope.CORE, "p1");
    store.append("归档 banana", MemoryScope.ARCHIVAL, "p1");

    assertFalse(store.recallByKeyword("banana", "p1").isEmpty());
    assertTrue(store.recallByKeyword("apple", "p1").isEmpty());
  }

  @Test
  @DisplayName("recall 把 % 当字面量而非通配符")
  void recallTreatsPercentAsLiteral() {
    store.append("alpha 记录", MemoryScope.ARCHIVAL, "p1");
    store.append("beta 记录", MemoryScope.ARCHIVAL, "p1");
    store.append("进度 50% 完成", MemoryScope.ARCHIVAL, "p1");

    // 未转义时 LIKE '%%%' 会命中全部 3 条；转义后只命中真正含 '%' 的那条
    List<String> hits = store.recallByKeyword("%", "p1");

    assertEquals(1, hits.size(), "搜 '%' 应只命中字面量含 % 的记录，实际: " + hits);
    assertTrue(hits.get(0).contains("50%"));
  }

  @Test
  @DisplayName("recall 把 _ 当字面量而非单字符通配符")
  void recallTreatsUnderscoreAsLiteral() {
    store.append("abc", MemoryScope.ARCHIVAL, "p1");
    store.append("xyz", MemoryScope.ARCHIVAL, "p1");
    store.append("snake_case 命名", MemoryScope.ARCHIVAL, "p1");

    // 未转义时 LIKE '%_%' 会命中任意非空内容（3 条全中）；转义后只命中含 '_' 的那条
    List<String> hits = store.recallByKeyword("_", "p1");

    assertEquals(1, hits.size(), "搜 '_' 应只命中字面量含 _ 的记录，实际: " + hits);
    assertTrue(hits.get(0).contains("snake_case"));
  }

  @Test
  @DisplayName("recall 把转义符 ! 自身当字面量")
  void recallTreatsEscapeCharAsLiteral() {
    store.append("重要!!! 提醒", MemoryScope.ARCHIVAL, "p1");
    store.append("普通记录", MemoryScope.ARCHIVAL, "p1");

    List<String> hits = store.recallByKeyword("!", "p1");

    assertEquals(1, hits.size(), "搜 '!' 应只命中字面量含 ! 的记录，实际: " + hits);
    assertTrue(hits.get(0).contains("!!!"));
  }

  @Test
  @DisplayName("不同 Profile 数据隔离")
  void profilesAreIsolated() {
    store.append("p1-val", MemoryScope.ARCHIVAL, "p1");
    store.append("p2-val", MemoryScope.ARCHIVAL, "p2");

    assertTrue(store.load("p1").contains("p1-val"));
    assertFalse(store.load("p1").contains("p2-val"));
  }

  @Test
  @DisplayName("核心区全量保留不受 LIMIT 影响")
  void coreNotLimited() {
    for (int i = 0; i < 200; i++) {
      store.append("核心 " + i, MemoryScope.CORE, "p1");
    }

    String loaded = store.load("p1");

    assertTrue(loaded.contains("核心 0"));
    assertTrue(loaded.contains("核心 199"));
  }
}
