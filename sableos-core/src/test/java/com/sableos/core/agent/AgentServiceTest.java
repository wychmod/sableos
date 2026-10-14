package com.sableos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileContext;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.react.ReActLoop;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionStore;
import com.sableos.core.trace.TraceContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 统一编排入口验收：处理期间线程级上下文可取到当前 Profile、异常路径 {@code finally} 也清、正常结束保存会话、未注册 Agent 点名报错。 */
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

  private static final String PROFILE_NAME = "ops-agent";

  @Mock ReActLoop reActLoop;

  @Mock SessionStore sessionStore;

  @AfterEach
  void tearDown() {
    ProfileContext.clear();
    TraceContext.clear();
  }

  private static Profile profile(String name) {
    return new Profile(
        name,
        "测试 Agent",
        new Profile.Identity(name, "你是运维助手。"),
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(null, null));
  }

  private AgentService serviceWithAgent() {
    ProfileRegistry registry = new ProfileRegistry();
    registry.register(profile(PROFILE_NAME));
    return new AgentService(registry, reActLoop, sessionStore);
  }

  private static Session session() {
    return new Session("s-1", PROFILE_NAME, "cli", "u-1");
  }

  @Test
  @DisplayName("处理期间线程级上下文可取到当前 Profile")
  void process_duringRun_profileContextHoldsCurrentProfile() {
    AtomicReference<Profile> captured = new AtomicReference<>();
    when(reActLoop.run(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              captured.set(ProfileContext.current());
              return "ok";
            });

    serviceWithAgent().process(session(), "你好");

    assertThat(captured.get()).isNotNull();
    assertThat(captured.get().name()).isEqualTo(PROFILE_NAME);
  }

  @Test
  @DisplayName("处理抛异常时 finally 也必须清掉上下文")
  void process_loopThrows_contextStillCleared() {
    when(reActLoop.run(any(), any(), any())).thenThrow(new IllegalStateException("boom"));
    AgentService service = serviceWithAgent();

    assertThatThrownBy(() -> service.process(session(), "你好"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(ProfileContext.current()).isNull();
    verify(sessionStore, never()).save(any());
  }

  @Test
  @DisplayName("处理完成后会话被保存")
  void process_afterLoop_savesSession() {
    when(reActLoop.run(any(), any(), any())).thenReturn("done");
    Session session = session();

    String reply = serviceWithAgent().process(session, "你好");

    assertThat(reply).isEqualTo("done");
    verify(sessionStore).save(session);
  }

  @Test
  @DisplayName("未注册的 Agent 点名报错，且不进循环")
  void process_unregisteredProfile_throwsNamed() {
    AgentService service = new AgentService(new ProfileRegistry(), reActLoop, sessionStore);
    Session session = new Session("s-1", "ghost", "cli", "u-1");

    assertThatThrownBy(() -> service.process(session, "你好"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ghost");

    verifyNoInteractions(reActLoop);
  }
}
