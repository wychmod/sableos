package com.sableos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 会话管理器验收（课件 harness）：同一三元组幂等、任一维度不同即隔离、session_id 只按 {@code channel:user:profile} 一处拼接。
 *
 * <p>真 Flyway + 临时 SQLite（本地默认档），零网络。方法名英文，课件中文原文进 {@link DisplayName}。
 */
@SpringBootTest(classes = StorageTestApplication.class)
class SessionManagerTest {

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
      Path path = Files.createTempFile("sableos-session-manager-", ".db");
      path.toFile().deleteOnExit();
      return path.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private SessionManager sessionManager;
  @Autowired private SessionRepository repository;

  @BeforeEach
  void cleanTable() {
    repository.deleteAll();
  }

  @Test
  @DisplayName("同一三元组_历次getOrCreate都是同一个Session")
  void getOrCreate_sameTriple_returnsSameSession() {
    Session first = sessionManager.getOrCreate("cli", "wang", "default");
    Session second = sessionManager.getOrCreate("cli", "wang", "default");

    // 幂等：多轮对话靠它串起来
    assertThat(first.id()).isEqualTo(second.id());
    assertThat(second.messages()).isEmpty();
    assertThat(sessionManager.get(first.id())).isPresent();
  }

  @Test
  @DisplayName("channel_user_profile任一不同则是不同Session")
  void getOrCreate_anyDimensionDiffers_returnsDifferentSession() {
    Session base = sessionManager.getOrCreate("cli", "wang", "default");

    // channel 不同就是不同会话
    Session otherChannel = sessionManager.getOrCreate("web", "wang", "default");
    assertThat(otherChannel.id()).isNotEqualTo(base.id());

    // user 不同
    Session otherUser = sessionManager.getOrCreate("cli", "li", "default");
    assertThat(otherUser.id()).isNotEqualTo(base.id());

    // profile 不同
    Session otherProfile = sessionManager.getOrCreate("cli", "wang", "weather");
    assertThat(otherProfile.id()).isNotEqualTo(base.id());

    assertThat(repository.count()).isEqualTo(4);
  }

  @Test
  @DisplayName("session_id按channel冒号user冒号profile一处拼接")
  void sessionId_matchesFormula_andIsSoleSource() {
    assertThat(SessionManager.sessionId("cli", "wang", "default")).isEqualTo("cli:wang:default");

    Session session = sessionManager.getOrCreate("scheduler", "scheduler", "digest");
    assertThat(session.id()).isEqualTo("scheduler:scheduler:digest");
    assertThat(session.channel()).isEqualTo("scheduler");
    assertThat(session.status()).isEqualTo("active");
  }

  @Test
  @DisplayName("会话列表_按最后活跃时间倒序且尊重上限")
  void listRecent_ordersByLastActiveDescendingAndLimits() {
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    repository.save(entity("old", base, base));
    repository.save(entity("newest", base, base.plusSeconds(20)));
    repository.save(entity("middle", base, base.plusSeconds(10)));

    List<Session> recent = sessionManager.listRecent(2);

    assertThat(recent).extracting(Session::id).containsExactly("newest", "middle");
  }

  @Test
  @DisplayName("会话列表_非正数上限返回空列表")
  void listRecent_nonPositiveLimit_returnsEmpty() {
    repository.save(entity("only", Instant.now(), Instant.now()));

    assertThat(sessionManager.listRecent(0)).isEmpty();
    assertThat(sessionManager.listRecent(-1)).isEmpty();
  }

  @Test
  @DisplayName("会话列表_状态过滤在存储侧完成_不被未命中行吃掉名额")
  void listRecent_filtersByStatusBeforeLimiting() {
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    // 三条更新的 active 排在前面；若先截断再过滤，archived 那条会被挤掉而返回空。
    repository.save(entity("a1", base, base.plusSeconds(30)));
    repository.save(entity("a2", base, base.plusSeconds(20)));
    repository.save(entity("a3", base, base.plusSeconds(10)));
    repository.save(entity("old-archived", "archived", base, base));

    List<Session> archived = sessionManager.listRecent(2, "archived");

    assertThat(archived).extracting(Session::id).containsExactly("old-archived");
    assertThat(sessionManager.listRecent(2, "active"))
        .extracting(Session::id)
        .containsExactly("a1", "a2");
  }

  private static SessionEntity entity(String id, Instant createdAt, Instant lastActiveAt) {
    return entity(id, "active", createdAt, lastActiveAt);
  }

  private static SessionEntity entity(
      String id, String status, Instant createdAt, Instant lastActiveAt) {
    SessionEntity entity = new SessionEntity();
    entity.setSessionId(id);
    entity.setProfileName("default");
    entity.setChannel("cli");
    entity.setUserId("wang");
    entity.setStatus(status);
    entity.setMessagesJson("[]");
    entity.setCreatedAt(createdAt);
    entity.setLastActiveAt(lastActiveAt);
    return entity;
  }

  @Test
  @DisplayName("getOrCreate命中既有行时带回已存历史_新建时为空")
  void getOrCreate_restoresPersistedHistory() {
    Session created = sessionManager.getOrCreate("cli", "wang", "default");
    created.appendUserMessage("你好");
    sessionManager.save(created);

    Session reloaded = sessionManager.getOrCreate("cli", "wang", "default");
    assertThat(reloaded.id()).isEqualTo(created.id());
    assertThat(reloaded.messages()).hasSize(1);
    assertThat(reloaded.messages().get(0).content()).isEqualTo("你好");
  }
}
