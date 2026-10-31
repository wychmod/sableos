package com.sableos.web.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sableos.core.agent.AgentService;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.web.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 会话四端点的 HTTP 契约：校验、404、只调用一次统一入口、历史截断、软归档。 */
class SessionApiControllerTest {

  private static final String SESSION_ID = "web:alice:default";

  private SessionManager sessionManager;
  private ProfileRegistry profileRegistry;
  private AgentService agentService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    sessionManager = mock(SessionManager.class);
    profileRegistry = mock(ProfileRegistry.class);
    agentService = mock(AgentService.class);
    AsyncTaskExecutor executor = Runnable::run;
    SessionApiController controller =
        new SessionApiController(
            sessionManager, profileRegistry, new AgentCallExecutor(agentService, executor));
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("消息超过32KB_返回400且不调用处理入口")
  void oversizedMessageReturns400WithoutCallingAgent() throws Exception {
    String body = "{\"content\":\"" + "x".repeat(ApiConstraints.MAX_MESSAGE_CHARS + 1) + "\"}";

    mvc.perform(
            post("/api/v1/sessions/" + SESSION_ID + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("会话不存在_发消息返回404")
  void missingSessionReturns404OnSend() throws Exception {
    when(sessionManager.get(SESSION_ID)).thenReturn(Optional.empty());

    mvc.perform(
            post("/api/v1/sessions/" + SESSION_ID + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"你好\"}".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("会话不存在: " + SESSION_ID));
  }

  @Test
  @DisplayName("正常发消息_处理入口恰被调用一次并返回答复")
  void normalSendCallsAgentExactlyOnce() throws Exception {
    Session session = newSession();
    when(sessionManager.get(SESSION_ID)).thenReturn(Optional.of(session));
    when(agentService.process(eq(session), eq("你好"))).thenReturn("你好，有什么可以帮你");

    mvc.perform(
            post("/api/v1/sessions/" + SESSION_ID + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"你好\"}".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.reply").value("你好，有什么可以帮你"));

    verify(agentService).process(eq(session), eq("你好"));
  }

  @Test
  @DisplayName("建会话_使用web渠道与缺省用户并返回会话视图")
  void createUsesWebChannelAndAnonymousUser() throws Exception {
    Session session = new Session("web:anonymous:default", "default", "web", "anonymous");
    when(profileRegistry.exists("default")).thenReturn(true);
    when(sessionManager.getOrCreate("web", "anonymous", "default")).thenReturn(session);

    mvc.perform(
            post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\":\"default\"}".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value("web:anonymous:default"))
        .andExpect(jsonPath("$.data.channel").value("web"))
        .andExpect(jsonPath("$.data.userId").value("anonymous"));

    verify(sessionManager).getOrCreate("web", "anonymous", "default");
  }

  @Test
  @DisplayName("建会话_未注册Agent返回404")
  void createUnknownAgentReturns404() throws Exception {
    when(profileRegistry.exists("ghost")).thenReturn(false);

    mvc.perform(
            post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\":\"ghost\"}".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("建会话_缺profile返回400")
  void createWithoutProfileReturns400() throws Exception {
    mvc.perform(
            post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"alice\"}".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("查历史_最多返回最近100条")
  void historyReturnsAtMostLast100Messages() throws Exception {
    Session session = newSession();
    for (int i = 0; i < 150; i++) {
      session.appendUserMessage("m" + i);
    }
    when(sessionManager.get(SESSION_ID)).thenReturn(Optional.of(session));

    mvc.perform(get("/api/v1/sessions/" + SESSION_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.messages", hasSize(100)))
        .andExpect(jsonPath("$.data.messages[0].content").value("m50"))
        .andExpect(jsonPath("$.data.messages[99].content").value("m149"));
  }

  @Test
  @DisplayName("归档_成功返回200并调用软归档")
  void archiveReturns200() throws Exception {
    when(sessionManager.archive(SESSION_ID)).thenReturn(true);

    mvc.perform(delete("/api/v1/sessions/" + SESSION_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    verify(sessionManager).archive(SESSION_ID);
  }

  @Test
  @DisplayName("归档不存在的会话_返回404")
  void archiveMissingSessionReturns404() throws Exception {
    when(sessionManager.archive("missing")).thenReturn(false);

    mvc.perform(delete("/api/v1/sessions/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("会话不存在: missing"));
  }

  private static Session newSession() {
    return Session.rehydrate(
        SESSION_ID, "default", "web", "alice", "active", Instant.now(), Instant.now(), null);
  }
}
