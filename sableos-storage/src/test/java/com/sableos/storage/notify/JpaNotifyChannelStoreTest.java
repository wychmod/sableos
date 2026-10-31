package com.sableos.storage.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.sableos.core.notify.NotifyChannelView;
import com.sableos.storage.StorageTestApplication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** notify_channels 注册表的 JPA 契约验收（第 28 节）：CRUD、顺序、创建时间保留。 */
@SpringBootTest(classes = StorageTestApplication.class)
class JpaNotifyChannelStoreTest {

  private static final String DB_URL = "jdbc:sqlite:" + tempDbPath();
  private static final Instant CREATED = Instant.parse("2026-10-31T00:00:00Z");

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
      Path path = Files.createTempFile("sableos-notify-store-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private JpaNotifyChannelStore store;
  @Autowired private NotifyChannelRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTable() {
    repository.deleteAll();
  }

  @Test
  @DisplayName("建表_列齐_迁移历史含V7")
  void table_hasRequiredColumns_andFlywayV7() {
    List<Map<String, Object>> columns =
        jdbcTemplate.queryForList("PRAGMA table_info(notify_channels)");
    Set<String> names =
        columns.stream().map(row -> String.valueOf(row.get("name"))).collect(Collectors.toSet());
    assertThat(names)
        .contains("name", "type", "url", "description", "config_json", "created_at", "updated_at");

    Integer versionCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ?", Integer.class, "7");
    assertThat(versionCount).isEqualTo(1);
  }

  @Test
  @DisplayName("新建后可按名查到_配置往返")
  void save_thenFind_roundTripsConfig() {
    store.save(channel("team", "webhook", "https://example.com/hook", Map.of("timeout", "3")));

    NotifyChannelView found = store.find("team").orElseThrow();
    assertThat(found.name()).isEqualTo("team");
    assertThat(found.type()).isEqualTo("webhook");
    assertThat(found.url()).isEqualTo("https://example.com/hook");
    assertThat(found.config()).containsEntry("timeout", "3");
    assertThat(found.createdAt()).isEqualTo(CREATED);
  }

  @Test
  @DisplayName("更新保留createdAt并改url")
  void save_existing_preservesCreatedAt() {
    store.save(channel("team", "webhook", "https://old.example.com/hook", Map.of()));

    store.save(
        new NotifyChannelView(
            "team", "webhook", "https://new.example.com/hook", "新地址", Map.of(), CREATED, CREATED));

    NotifyChannelView found = store.find("team").orElseThrow();
    assertThat(found.url()).isEqualTo("https://new.example.com/hook");
    assertThat(found.description()).isEqualTo("新地址");
    assertThat(found.createdAt()).isEqualTo(CREATED);
    assertThat(found.updatedAt()).isAfterOrEqualTo(CREATED);
  }

  @Test
  @DisplayName("列表按创建时间升序_同刻按名称")
  void list_ordersByCreatedAtThenName() {
    store.save(channel("b", "webhook", "https://b.example.com/hook", Map.of()));
    store.save(channel("a", "webhook", "https://a.example.com/hook", Map.of()));

    assertThat(store.list()).extracting(NotifyChannelView::name).containsExactly("a", "b");
  }

  @Test
  @DisplayName("删除不存在返回false_删除后不可查")
  void delete_removesAndRejectsMissing() {
    store.save(channel("team", "webhook", "https://example.com/hook", Map.of()));

    assertThat(store.delete("team")).isTrue();
    assertThat(store.find("team")).isEmpty();
    assertThat(store.delete("team")).isFalse();
  }

  private static NotifyChannelView channel(
      String name, String type, String url, Map<String, String> config) {
    return new NotifyChannelView(name, type, url, null, config, CREATED, CREATED);
  }
}
