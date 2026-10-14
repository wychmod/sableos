package com.sableos.storage;

import static org.assertj.core.api.Assertions.assertThat;

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
 * tool_invocations 存取验收：建表走手工 Flyway 脚本（拒绝 ddl-auto），成败两态能存能读， {@code success}/{@code
 * error_message}/{@code trace_id} 列经 PRAGMA 真实存在，trace_id 与链路上下文一致，迁移历史含 V1 与 V2。
 */
@SpringBootTest(classes = StorageTestApplication.class)
class ToolInvocationRepositoryTest {

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
      Path path = Files.createTempFile("sableos-tool-invocation-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private ToolInvocationRepository repository;
  @Autowired private JpaToolInvocationAuditor auditor;
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
      auditor.record("s-1", "http_get", "{\"city\":\"bj\"}", "{\"temp\":20}", true, null, 123L);
    }
    auditor.record("s-1", "http_get", "{}", null, false, "工具执行异常：boom", 5L);

    List<ToolInvocation> rows = new ArrayList<>(repository.findAll());
    rows.sort(Comparator.comparing(ToolInvocation::getId));
    assertThat(rows).hasSize(2);

    ToolInvocation success = rows.get(0);
    assertThat(success.isSuccess()).isTrue();
    assertThat(success.getSessionId()).isEqualTo("s-1");
    assertThat(success.getToolName()).isEqualTo("http_get");
    assertThat(success.getInputJson()).isEqualTo("{\"city\":\"bj\"}");
    assertThat(success.getResultJson()).isEqualTo("{\"temp\":20}");
    assertThat(success.getErrorMessage()).isNull();
    assertThat(success.getDurationMs()).isEqualTo(123L);
    assertThat(success.getCreatedAt()).isNotNull();

    ToolInvocation failed = rows.get(1);
    assertThat(failed.isSuccess()).isFalse();
    assertThat(failed.getErrorMessage()).isEqualTo("工具执行异常：boom");
    assertThat(failed.getResultJson()).isNull();
    assertThat(failed.getDurationMs()).isEqualTo(5L);
  }

  @Test
  @DisplayName("审计_trace_id与链路上下文一致_无上下文为null")
  void record_traceId_followsContext() {
    String scopeTraceId;
    try (TraceContext.Scope scope = TraceContext.openRoot(TraceContext.CHANNEL_CLI)) {
      scopeTraceId = scope.traceId();
      auditor.record("s-trace", "http_get", "{}", "ok", true, null, 1L);
    }
    auditor.record("s-no-trace", "http_get", "{}", "ok", true, null, 1L);

    assertThat(findBySession("s-trace").getTraceId()).isEqualTo(scopeTraceId);
    assertThat(findBySession("s-no-trace").getTraceId()).isNull();
  }

  @Test
  @DisplayName("建表_手工脚本建出的表真实存在required列_迁移历史含V1与V2")
  void table_hasRequiredColumnsViaPragma() {
    List<Map<String, Object>> columns =
        jdbcTemplate.queryForList("PRAGMA table_info(tool_invocations)");
    Set<String> names =
        columns.stream().map(row -> String.valueOf(row.get("name"))).collect(Collectors.toSet());

    assertThat(names)
        .contains(
            "id",
            "session_id",
            "tool_name",
            "input_json",
            "result_json",
            "success",
            "error_message",
            "duration_ms",
            "trace_id",
            "created_at");
    Integer v1 =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1'", Integer.class);
    Integer v2 =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2'", Integer.class);
    assertThat(v1).isEqualTo(1);
    assertThat(v2).isEqualTo(1); // 双轨脚本按节追加，V1/V2 都由手工迁移执行
  }

  private ToolInvocation findBySession(String sessionId) {
    return repository.findAll().stream()
        .filter(row -> sessionId.equals(row.getSessionId()))
        .findFirst()
        .orElseThrow();
  }
}
