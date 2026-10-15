package com.sableos.channel.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sableos.core.agent.AgentService;
import com.sableos.core.session.Session;
import com.sableos.core.session.SessionManager;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CliChannel 交互壳验收（非课件 harness，属本节加的薄壳单测）：逐行转交引擎、打印回复、{@code /quit}/EOF 退出、空行跳过； 且 channel 以字面量
 * {@code "cli"} 作为三元组参数交给 SessionManager（自身不拼 session_id）。
 */
@ExtendWith(MockitoExtension.class)
class CliChannelTest {

  @Mock private AgentService agentService;
  @Mock private SessionManager sessionManager;

  private static BufferedReader reader(String script) {
    return new BufferedReader(
        new InputStreamReader(
            new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8));
  }

  private static PrintStream sink(ByteArrayOutputStream buf) {
    return new PrintStream(buf, true, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("逐行转交引擎并打印回复_读到quit退出")
  void run_forwardsEachLine_andStopsOnQuit() {
    Session session = new Session("cli:u:default", "default", "cli", "u");
    when(sessionManager.getOrCreate("cli", "u", "default")).thenReturn(session);
    when(agentService.process(eq(session), any())).thenReturn("回复");

    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    CliChannel channel = new CliChannel(agentService, sessionManager);
    channel.run("default", "u", reader("你好\n今天天气\n/quit\n被忽略\n"), sink(buf));

    verify(agentService, times(1)).process(session, "你好");
    verify(agentService, times(1)).process(session, "今天天气");
    verify(agentService, never()).process(session, "/quit");
    verify(agentService, never()).process(session, "被忽略");
    String printed = buf.toString(StandardCharsets.UTF_8);
    assertThat(printed).contains("回复").contains("再见。");
  }

  @Test
  @DisplayName("EOF等同退出_空行跳过不转交")
  void run_stopsOnEof_andSkipsBlankLines() {
    Session session = new Session("cli:u:default", "default", "cli", "u");
    when(sessionManager.getOrCreate("cli", "u", "default")).thenReturn(session);
    when(agentService.process(eq(session), any())).thenReturn("回复");

    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    CliChannel channel = new CliChannel(agentService, sessionManager);
    // 一个空行 + 一句有效 + 无 /quit（EOF 结束）
    channel.run("default", "u", reader("\n你好\n"), sink(buf));

    verify(agentService, times(1)).process(session, "你好");
    verify(agentService, never()).process(session, "");
    assertThat(buf.toString(StandardCharsets.UTF_8)).contains("再见。");
  }
}
