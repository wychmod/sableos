package com.sableos.storage.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.sableos.core.scheduler.ScheduledTaskView;
import com.sableos.core.scheduler.TaskExecutionView;
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

/** scheduled_tasks / task_executions 的 JPA 契约验收（第 28 节）：稳定标识、退役复活、历史与状态更新。 */
@SpringBootTest(classes = StorageTestApplication.class)
class JpaScheduledTaskStoreTest {

  private static final String DB_URL = "jdbc:sqlite:" + tempDbPath();
  private static final Instant NEXT_RUN = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant STARTED = Instant.parse("2026-10-31T00:00:00Z");

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
      Path path = Files.createTempFile("sableos-scheduler-store-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private JpaScheduledTaskStore store;
  @Autowired private ScheduledTaskRepository taskRepository;
  @Autowired private TaskExecutionRepository executionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTables() {
    executionRepository.deleteAll();
    taskRepository.deleteAll();
  }

  @Test
  @DisplayName("建表_两表列齐_迁移历史含V6")
  void tables_haveRequiredColumns_andFlywayV6() {
    Set<String> taskColumns = columnNames("scheduled_tasks");
    assertThat(taskColumns)
        .contains(
            "schedule_id",
            "profile_name",
            "schedule_key",
            "display_name",
            "cron",
            "zone",
            "message",
            "enabled",
            "retired",
            "next_run_at",
            "last_run_at",
            "last_status",
            "run_count",
            "updated_at");

    Set<String> executionColumns = columnNames("task_executions");
    assertThat(executionColumns)
        .contains(
            "id",
            "schedule_id",
            "session_id",
            "started_at",
            "success",
            "error_message",
            "duration_ms");

    Integer versionCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ?", Integer.class, "6");
    assertThat(versionCount).isEqualTo(1);
  }

  @Test
  @DisplayName("同Agent同任务键_reconcile复用稳定标识并保留启用状态与累计次数")
  void reconcile_reusesStableId_andPreservesRuntimeState() {
    ScheduledTaskView created = reconcile("demo", "daily", "0 0 8 * * *");
    store.setEnabled(created.scheduleId(), false);
    store.recordExecution(
        created.scheduleId(), "scheduler:scheduler:demo", STARTED, true, null, 12, NEXT_RUN);

    ScheduledTaskView updated = reconcile("demo", "daily", "0 30 8 * * *");

    assertThat(updated.scheduleId()).isEqualTo(created.scheduleId());
    assertThat(updated.cron()).isEqualTo("0 30 8 * * *");
    assertThat(updated.enabled()).isFalse();
    assertThat(updated.runCount()).isEqualTo(1);
    assertThat(updated.lastStatus()).isEqualTo("success");
  }

  @Test
  @DisplayName("退役行保留历史_配置再现时复活且标识不变")
  void retire_keepsHistory_andReconcileRevives() {
    ScheduledTaskView created = reconcile("demo", "daily", "0 0 8 * * *");
    store.recordExecution(
        created.scheduleId(), "scheduler:scheduler:demo", STARTED, true, null, 12, NEXT_RUN);

    store.retire(created.scheduleId());
    assertThat(store.find(created.scheduleId()))
        .get()
        .extracting(ScheduledTaskView::retired)
        .isEqualTo(true);
    assertThat(store.list()).isEmpty();
    assertThat(store.executions(created.scheduleId())).hasSize(1);

    ScheduledTaskView revived = reconcile("demo", "daily", "0 0 9 * * *");

    assertThat(revived.scheduleId()).isEqualTo(created.scheduleId());
    assertThat(revived.retired()).isFalse();
    assertThat(revived.runCount()).isEqualTo(1);
    assertThat(store.list()).hasSize(1);
  }

  @Test
  @DisplayName("recordExecution_同事务更新任务状态并写入历史")
  void recordExecution_updatesTaskAndWritesHistory() {
    ScheduledTaskView created = reconcile("demo", "daily", "0 0 8 * * *");

    TaskExecutionView execution =
        store
            .recordExecution(
                created.scheduleId(), "scheduler:scheduler:demo", STARTED, true, null, 45, NEXT_RUN)
            .orElseThrow();

    assertThat(execution.executionId()).isPositive();
    assertThat(execution.scheduleId()).isEqualTo(created.scheduleId());
    assertThat(execution.success()).isTrue();
    assertThat(execution.errorMessage()).isNull();
    assertThat(execution.durationMs()).isEqualTo(45);

    ScheduledTaskView updated = store.find(created.scheduleId()).orElseThrow();
    assertThat(updated.runCount()).isEqualTo(1);
    assertThat(updated.lastStatus()).isEqualTo("success");
    assertThat(updated.lastRunAt()).isEqualTo(STARTED);
    assertThat(updated.nextRunAt()).isEqualTo(NEXT_RUN);
    assertThat(store.executions(created.scheduleId())).hasSize(1);
  }

  @Test
  @DisplayName("失败执行_历史保留可读原因且累计次数照加")
  void recordExecution_failure_keepsReadableMessage() {
    ScheduledTaskView created = reconcile("demo", "daily", "0 0 8 * * *");

    TaskExecutionView execution =
        store
            .recordExecution(
                created.scheduleId(),
                "scheduler:scheduler:demo",
                STARTED,
                false,
                "域名不在白名单内: evil.com",
                30,
                NEXT_RUN)
            .orElseThrow();

    assertThat(execution.success()).isFalse();
    assertThat(execution.errorMessage()).isEqualTo("域名不在白名单内: evil.com");
    ScheduledTaskView updated = store.find(created.scheduleId()).orElseThrow();
    assertThat(updated.lastStatus()).isEqualTo("failed");
    assertThat(updated.runCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("记录不存在的任务_返回空且不写历史")
  void recordExecution_missingTask_returnsEmpty() {
    assertThat(
            store.recordExecution(
                9999L, "scheduler:scheduler:demo", STARTED, true, null, 1, NEXT_RUN))
        .isEmpty();
    assertThat(executionRepository.count()).isZero();
  }

  @Test
  @DisplayName("启用开关_缺失任务返回false")
  void setEnabled_togglesAndRejectsMissing() {
    ScheduledTaskView created = reconcile("demo", "daily", "0 0 8 * * *");
    assertThat(store.isEnabled(created.scheduleId())).isTrue();

    assertThat(store.setEnabled(created.scheduleId(), false)).isTrue();
    assertThat(store.isEnabled(created.scheduleId())).isFalse();

    assertThat(store.setEnabled(9999L, true)).isFalse();
    assertThat(store.isEnabled(9999L)).isFalse();
  }

  @Test
  @DisplayName("活动列表按Agent与任务键排序_退役行不出现")
  void list_ordersAndExcludesRetired() {
    ScheduledTaskView b = reconcile("b-agent", "b-task", "0 0 8 * * *");
    reconcile("a-agent", "a-task", "0 0 8 * * *");
    store.retire(b.scheduleId());

    assertThat(store.list())
        .extracting(ScheduledTaskView::profileName, ScheduledTaskView::scheduleKey)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("a-agent", "a-task"));
  }

  @Test
  @DisplayName("执行历史按开始时刻倒序")
  void executions_orderByStartedAtDesc() {
    ScheduledTaskView created = reconcile("demo", "daily", "0 0 8 * * *");
    Instant later = STARTED.plusSeconds(60);
    store.recordExecution(
        created.scheduleId(), "scheduler:scheduler:demo", STARTED, true, null, 1, NEXT_RUN);
    store.recordExecution(
        created.scheduleId(), "scheduler:scheduler:demo", later, true, null, 2, NEXT_RUN);

    List<TaskExecutionView> executions = store.executions(created.scheduleId());
    assertThat(executions).hasSize(2);
    assertThat(executions.get(0).startedAt()).isEqualTo(later);
    assertThat(executions.get(1).startedAt()).isEqualTo(STARTED);
  }

  private ScheduledTaskView reconcile(String profile, String key, String cron) {
    return store.reconcile(profile, key, key, cron, "Asia/Shanghai", "到点执行", NEXT_RUN);
  }

  private Set<String> columnNames(String table) {
    List<Map<String, Object>> columns =
        jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")");
    return columns.stream().map(row -> String.valueOf(row.get("name"))).collect(Collectors.toSet());
  }
}
