package com.sableos.core.agent;

import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.provider.ProviderResponse;
import com.sableos.core.react.ReActLoop;
import com.sableos.core.session.MessageRole;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.core.session.SessionMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * 回归：同一会话的两个并发请求各自从存储加载独立 Session 实例时，不能丢失任何一轮对话。
 *
 * <p>建模真实路径（{@code SessionApiController#send}）：每次请求 {@code get(id)} 拿到新实例 → {@code process} →
 * {@code save}。存储侧用「整行覆盖 messages」模拟数据库，每次 get 返回新实例，与 {@code JpaSessionManager.restore} 一致。
 */
class AgentServiceConcurrencyTest {

  @Test
  @DisplayName("并发处理同一会话_两轮对话都不丢")
  void concurrentProcessKeepsBothTurns() throws Exception {
    AtomicReference<List<SessionMessage>> row = new AtomicReference<>(new ArrayList<>());
    CountDownLatch bothLoaded = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);

    SessionManager store = new InMemoryManager(row);

    ReActLoop loop = Mockito.mock(ReActLoop.class);
    Mockito.when(loop.run(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenAnswer(
            inv -> {
              Session s = inv.getArgument(0);
              String msg = inv.getArgument(1);
              bothLoaded.countDown();
              release.await(5, TimeUnit.SECONDS);
              s.appendUserMessage(msg);
              s.appendAssistantMessage(Mockito.mock(ProviderResponse.class));
              return "reply";
            });

    AgentService service = new AgentService(registry(), loop, store);

    Thread t1 = new Thread(() -> runRequest(service, store, "消息A", bothLoaded, release));
    Thread t2 = new Thread(() -> runRequest(service, store, "消息B", bothLoaded, release));
    t1.start();
    t2.start();
    bothLoaded.await(5, TimeUnit.SECONDS);
    release.countDown();
    t1.join(10_000);
    t2.join(10_000);

    List<String> users =
        row.get().stream()
            .filter(m -> m.role() == MessageRole.USER)
            .map(SessionMessage::content)
            .toList();
    System.out.println(">>> 持久化后的用户消息: " + users);
    Assertions.assertEquals(2, users.size(), "两条并发消息都应落库，实际只留下: " + users);
  }

  private static void runRequest(
      AgentService service,
      SessionManager store,
      String msg,
      CountDownLatch loaded,
      CountDownLatch release) {
    Session s = store.get("web:u1:agent").orElseThrow();
    loaded.countDown();
    try {
      release.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    service.process(s, msg);
  }

  /** 单行存储：save 整行覆盖，get 每次返回独立实例（与 JpaSessionManager 一致）。 */
  private record InMemoryManager(AtomicReference<List<SessionMessage>> row)
      implements SessionManager {

    @Override
    public void save(Session session) {
      row.set(new ArrayList<>(session.messages()));
    }

    @Override
    public Session getOrCreate(String channel, String userId, String profileName) {
      return get(SessionManager.sessionId(channel, userId, profileName)).orElseThrow();
    }

    @Override
    public Optional<Session> get(String sessionId) {
      return Optional.of(
          Session.rehydrate(
              sessionId,
              "agent",
              "web",
              "u1",
              "active",
              Instant.now(),
              Instant.now(),
              new ArrayList<>(row.get())));
    }

    @Override
    public boolean archive(String sessionId) {
      return true;
    }

    @Override
    public List<Session> listRecent(int limit, String status) {
      return List.of(get("web:u1:agent").orElseThrow());
    }
  }

  private static ProfileRegistry registry() {
    ProfileRegistry registry = new ProfileRegistry();
    registry.register(
        new Profile(
            "agent",
            "测试",
            new Profile.Identity("agent", "你是助手。"),
            new Profile.ProviderRef("mock", "mock-1", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(null, null)));
    return registry;
  }
}
