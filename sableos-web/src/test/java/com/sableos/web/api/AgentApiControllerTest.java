package com.sableos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sableos.core.agent.AgentService;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import com.sableos.web.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 无状态调用端点：404、参数校验、一次性会话互不复用。 */
class AgentApiControllerTest {

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
    AgentApiController controller =
        new AgentApiController(
            sessionManager, profileRegistry, new AgentCallExecutor(agentService, executor));
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("Agent未注册_返回404且不进入处理链")
  void unknownAgentReturns404() throws Exception {
    when(profileRegistry.exists("ghost")).thenReturn(false);

    mvc.perform(invoke("ghost", "你好"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Agent 不存在: ghost"));

    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("消息为空_返回400")
  void blankMessageReturns400() throws Exception {
    when(profileRegistry.exists("default")).thenReturn(true);

    mvc.perform(invoke("default", "  "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("消息超过32KB_返回400")
  void oversizedMessageReturns400() throws Exception {
    when(profileRegistry.exists("default")).thenReturn(true);
    String body = "{\"message\":\"" + "x".repeat(ApiConstraints.MAX_MESSAGE_CHARS + 1) + "\"}";

    mvc.perform(
            post("/api/v1/agents/default/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("连续两次调用_使用两个不同的一次性会话")
  void twoInvocationsUseDifferentSessions() throws Exception {
    when(profileRegistry.exists("default")).thenReturn(true);
    when(sessionManager.getOrCreate(eq("web"), anyString(), eq("default")))
        .thenAnswer(
            invocation -> {
              String userId = invocation.getArgument(1);
              return new Session("web:" + userId + ":default", "default", "web", userId);
            });
    when(agentService.process(any(), any())).thenReturn("ok");

    mvc.perform(invoke("default", "第一次")).andExpect(status().isOk());
    mvc.perform(invoke("default", "第二次")).andExpect(status().isOk());

    ArgumentCaptor<Session> sessions = ArgumentCaptor.forClass(Session.class);
    verify(agentService, times(2)).process(sessions.capture(), any());
    assertThat(sessions.getAllValues()).extracting(Session::id).doesNotHaveDuplicates();
    assertThat(sessions.getAllValues())
        .allSatisfy(session -> assertThat(session.userId()).startsWith("invoke-"));
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder invoke(
      String name, String message) {
    return post("/api/v1/agents/" + name + "/invoke")
        .contentType(MediaType.APPLICATION_JSON)
        .content(("{\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
  }
}
