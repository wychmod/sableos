package com.sableos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sableos.core.provider.Usage;
import com.sableos.core.trace.TraceContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * llm_calls 存取验收：建表走手工 Flyway 脚本（拒绝 ddl-auto），成败两态能存能读， {@code success}/{@code
 * error_message}/{@code trace_id} 列经 PRAGMA 真实存在，trace_id 与链路上下文一致。
 */
@SpringBootTest(classes = StorageTestApplication.class)
class LlmCallRepositoryTest {

  private static final String DB_URL = "jdbc:sqlite:" + tempDbPath();

  @DynamicPropertySource
  static void overrideDatasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DB_URL);
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration/{vendor}");
    // 与 boot application.yaml 同一口径：Instant 走 setTimestamp 绑定，sqlite 以 INTEGER 毫秒往返
    registry.add(
        "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type", () -> "TIMESTAMP");
  }

  private static String tempDbPath() {
    try {
      Path path = Files.createTempFile("sableos-storage-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private LlmCallRepository repository;
  @Autowired private JpaLlmCallAuditor auditor;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTable() {
    repository.deleteAll();
  }

  @AfterEach
  void clearTraceContext() {
    TraceContext.clear();
  }

  @Test
  @DisplayName("审计_成败两态存取往返")
  void record_bothStates_roundTrips() {
    try (TraceContext.Scope ignored = TraceContext.openRoot(TraceContext.CHANNEL_WEB)) {
      auditor.record("s-1", "deepseek", "deepseek-chat", new Usage(10, 20, 30), true, null, 123L);
    }
    auditor.record("s-1", "deepseek", "deepseek-chat", null, false, "connect timeout", 5L);

    List<LlmCall> rows = new ArrayList<>(repository.findAll());
    rows.sort(Comparator.comparing(LlmCall::getId));
    assertThat(rows).hasSize(2);

    LlmCall success = rows.get(0);
    assertThat(success.isSuccess()).isTrue();
    assertThat(success.getSessionId()).isEqualTo("s-1");
    assertThat(success.getProvider()).isEqualTo("deepseek");
    assertThat(success.getModel()).isEqualTo("deepseek-chat");
    assertThat(success.getPromptTokens()).isEqualTo(10);
    assertThat(success.getCompletionTokens()).isEqualTo(20);
    assertThat(success.getTotalTokens()).isEqualTo(30);
    assertThat(success.getErrorMessage()).isNull();
    assertThat(success.getDurationMs()).isEqualTo(123L);
    assertThat(success.getCreatedAt()).isNotNull();

    LlmCall failed = rows.get(1);
    assertThat(failed.isSuccess()).isFalse();
    assertThat(failed.getErrorMessage()).isEqualTo("connect timeout");
    assertThat(failed.getPromptTokens()).isNull();
    assertThat(failed.getDurationMs()).isEqualTo(5L);
  }

  @Test
  @DisplayName("审计_trace_id与链路上下文一致_无上下文为null")
  void record_traceId_followsContext() {
    String scopeTraceId;
    try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_CLI)) {
      scopeTraceId = scope.traceId();
      auditor.record("s-trace", "deepseek", "deepseek-chat", null, true, null, 1L);
    }
    auditor.record("s-no-trace", "deepseek", "deepseek-chat", null, true, null, 1L);

    assertThat(findBySession("s-trace").getTraceId()).isEqualTo(scopeTraceId);
    assertThat(findBySession("s-no-trace").getTraceId()).isNull();
  }

  @Test
  @DisplayName("建表_手工脚本建出的表真实存在required列")
  void table_hasRequiredColumnsViaPragma() {
    List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(llm_calls)");
    Set<String> names =
        columns.stream().map(row -> String.valueOf(row.get("name"))).collect(Collectors.toSet());

    assertThat(names)
        .contains(
            "id",
            "session_id",
            "provider",
            "model",
            "prompt_tokens",
            "completion_tokens",
            "total_tokens",
            "trace_id",
            "success",
            "error_message",
            "duration_ms",
            "created_at");
    Integer applied =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1'", Integer.class);
    assertThat(applied).isEqualTo(1); // 表来自手工迁移脚本，不是 ddl-auto
  }

  private LlmCall findBySession(String sessionId) {
    return repository.findAll().stream()
        .filter(row -> sessionId.equals(row.getSessionId()))
        .findFirst()
        .orElseThrow();
  }
}
