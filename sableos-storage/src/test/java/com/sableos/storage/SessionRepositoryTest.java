package com.sableos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sableos.core.provider.ProviderResponse;
import com.sableos.core.provider.ToolCallRequest;
import com.sableos.core.session.MessageRole;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.core.session.SessionMessage;
import com.sableos.core.tool.ToolResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
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

/**
 * sessions 表存取验收（课件 harness）：手工 Flyway 脚本建出的表列齐、迁移历史含 V1/V2/V3；messages_json 序列化回读后各角色字段完整；
 * 模拟"重启"（以全新 manager 重查，回落库）历史仍在。
 */
@SpringBootTest(classes = StorageTestApplication.class)
class SessionRepositoryTest {

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
      Path path = Files.createTempFile("sableos-session-repo-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private SessionRepository repository;
  @Autowired private SessionManager sessionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTable() {
    repository.deleteAll();
  }

  @Test
  @DisplayName("建表_手工脚本建出的sessions表列齐_迁移历史含V1V2V3")
  void sessionsTable_hasRequiredColumns_viaPragma() {
    List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(sessions)");
    Set<String> names =
        columns.stream().map(row -> String.valueOf(row.get("name"))).collect(Collectors.toSet());

    assertThat(names)
        .contains(
            "session_id",
            "profile_name",
            "channel",
            "user_id",
            "messages_json",
            "status",
            "created_at",
            "last_active_at",
            "archived_at");

    for (String version : List.of("1", "2", "3")) {
      Integer count =
          jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ?",
              Integer.class,
              version);
      assertThat(count).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("messages_json序列化回读后各角色字段完整")
  void messagesJson_roundTrips_allFields() {
    Session session = sessionManager.getOrCreate("cli", "wang", "default");
    session.appendUserMessage("看看今天天气");
    session.appendAssistantMessage(
        new ProviderResponse(
            "", List.of(new ToolCallRequest("c1", "http_get", "{\"city\":\"bj\"}")), null));
    session.appendToolResult("http_get", ToolResult.success("{\"temp\":20}"));
    sessionManager.save(session);

    Session reloaded = sessionManager.get(session.id()).orElseThrow();
    List<SessionMessage> messages = reloaded.messages();
    assertThat(messages).hasSize(3);

    SessionMessage user = messages.get(0);
    assertThat(user.role()).isEqualTo(MessageRole.USER);
    assertThat(user.content()).isEqualTo("看看今天天气");
    assertThat(user.toolCalls()).isEmpty();
    assertThat(user.toolName()).isNull();
    assertThat(user.success()).isNull();

    SessionMessage assistant = messages.get(1);
    assertThat(assistant.role()).isEqualTo(MessageRole.ASSISTANT);
    assertThat(assistant.toolCalls()).hasSize(1);
    assertThat(assistant.toolCalls().get(0).id()).isEqualTo("c1");
    assertThat(assistant.toolCalls().get(0).name()).isEqualTo("http_get");
    assertThat(assistant.toolCalls().get(0).argumentsJson()).isEqualTo("{\"city\":\"bj\"}");

    SessionMessage tool = messages.get(2);
    assertThat(tool.role()).isEqualTo(MessageRole.TOOL);
    assertThat(tool.content()).isEqualTo("{\"temp\":20}");
    assertThat(tool.toolName()).isEqualTo("http_get");
    assertThat(tool.success()).isTrue();
  }

  @Test
  @DisplayName("模拟重启_新建上下文重查历史仍在")
  void restart_newContext_historyPersists() {
    Session session = sessionManager.getOrCreate("cli", "wang", "default");
    session.appendUserMessage("第一句");
    session.appendAssistantMessage(new ProviderResponse("第一答", List.of(), null));
    sessionManager.save(session);

    // 物理落库证据：裸 SQL 直接看到行与 messages_json（不依赖 JPA 一级缓存）
    String rawJson =
        jdbcTemplate.queryForObject(
            "SELECT messages_json FROM sessions WHERE session_id = ?", String.class, session.id());
    assertThat(rawJson).isNotBlank().contains("第一句").contains("第一答");

    // 模拟重启：用一个全新的 JpaSessionManager（无任何内存态）重查——每次都回落到库
    SessionManager restarted = new JpaSessionManager(repository);
    Session reloaded = restarted.getOrCreate("cli", "wang", "default");
    assertThat(reloaded.id()).isEqualTo(session.id());
    assertThat(reloaded.messages()).hasSize(2);
    assertThat(reloaded.messages().get(0).content()).isEqualTo("第一句");
    assertThat(reloaded.messages().get(1).content()).isEqualTo("第一答");
    // createdAt 被还原为原始值（非 now）；SQLite 以毫秒存储，故按毫秒粒度比较
    assertThat(reloaded.createdAt().truncatedTo(ChronoUnit.MILLIS))
        .isEqualTo(session.createdAt().truncatedTo(ChronoUnit.MILLIS));
  }
}
