package com.sableos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileContext;
import com.sableos.core.tool.ToolResult;
import com.sableos.tool.notify.NotifyChannelAdapter;
import com.sableos.tool.notify.NotifyTarget;
import com.sableos.tool.sandbox.ActionType;
import com.sableos.tool.sandbox.FileSandboxProperties;
import com.sableos.tool.sandbox.HttpSandboxProperties;
import com.sableos.tool.sandbox.Sandbox;
import com.sableos.tool.sandbox.SandboxViolationException;
import com.sableos.tool.sandbox.ShellSandboxProperties;
import com.sableos.tool.sandbox.WhitelistSandbox;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 19 节登记、24 节补跑的通知工具 harness：渠道解析 + 白名单拦截 + "先校验后发送"顺序。
 *
 * <p>顺序断言是本节最值钱的一条——白名单不能被"往外推"绕过。规则细节由 {@link com.sableos.tool.sandbox.WhitelistSandboxTest}
 * 覆盖，本类只钉接线。
 */
class NotifyToolsTest {

  private static final String WEBHOOK_URL = "https://hooks.example.com/team";

  private static final NotifyTarget TARGET =
      new NotifyTarget("webhook", Map.of("url", WEBHOOK_URL));

  private final Sandbox sandbox = mock(Sandbox.class);
  private final NotifyChannelAdapter adapter = mock(NotifyChannelAdapter.class);
  private final NotifyTools tools = new NotifyTools(sandbox, adapter, Map.of("team", TARGET));

  @AfterEach
  void clearProfileContext() {
    ProfileContext.clear();
  }

  private static ObjectNode input(String content, String channel) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("content", content);
    if (channel != null) {
      node.put("channel", channel);
    }
    return node;
  }

  private static void enterAgentContext() {
    ProfileContext.set(new Profile("demo", null, null, null, null, null, null, null, null, null));
  }

  @Test
  @DisplayName("无已注册渠道_明确报错且零发送")
  void noRegisteredChannel_failsWithoutSending() {
    NotifyTools noChannels = new NotifyTools(sandbox, adapter, Map.of());
    enterAgentContext();

    ToolResult result = noChannels.execute(input("hello", null));

    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("无已注册的通知渠道"), result.errorMessage());
    verifyNoInteractions(sandbox, adapter);
  }

  @Test
  @DisplayName("缺少 content_明确报错且零校验零发送")
  void missingContent_failsWithoutEnforceOrSend() {
    enterAgentContext();

    ToolResult result = tools.execute(input("   ", null));

    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("content"), result.errorMessage());
    verifyNoInteractions(sandbox, adapter);
  }

  @Test
  @DisplayName("缺少 Agent 上下文_明确报错且零校验零发送")
  void missingProfileContext_failsWithoutEnforceOrSend() {
    ProfileContext.clear();

    ToolResult result = tools.execute(input("hello", "team"));

    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("上下文"), result.errorMessage());
    verifyNoInteractions(sandbox, adapter);
  }

  @Test
  @DisplayName("channel 缺省_取第一个已注册渠道")
  void defaultChannel_usesFirstRegistered() {
    Map<String, NotifyTarget> channels = new LinkedHashMap<>();
    NotifyTarget first =
        new NotifyTarget("webhook", Map.of("url", "https://first.example.com/hook"));
    NotifyTarget second =
        new NotifyTarget("webhook", Map.of("url", "https://second.example.com/hook"));
    channels.put("first", first);
    channels.put("second", second);
    NotifyTools multi = new NotifyTools(sandbox, adapter, channels);
    enterAgentContext();

    ToolResult result = multi.execute(input("hello", null));

    assertTrue(result.success(), result.errorMessage());
    verify(adapter).send(eq(first), eq("hello"));
  }

  @Test
  @DisplayName("发送前必须先过白名单校验")
  void enforcePrecedesSend() {
    enterAgentContext();

    ToolResult result = tools.execute(input("hello", "team"));

    assertTrue(result.success(), result.errorMessage());
    InOrder inOrder = inOrder(sandbox, adapter);
    inOrder
        .verify(sandbox)
        .enforce(
            argThat(
                action ->
                    action.type() == ActionType.HTTP_REQUEST
                        && WEBHOOK_URL.equals(action.target())));
    inOrder.verify(adapter).send(eq(TARGET), eq("hello"));
  }

  @Test
  @DisplayName("webhook 域名不在白名单_拦截且零发送")
  void domainOutsideWhitelist_doesNotSend() {
    WhitelistSandbox whitelist =
        new WhitelistSandbox(
            new FileSandboxProperties(List.of()),
            new ShellSandboxProperties(List.of()),
            new HttpSandboxProperties(List.of("allowed.example.com")));
    NotifyTools guarded = new NotifyTools(whitelist, adapter, Map.of("team", TARGET));
    enterAgentContext();

    assertThrows(SandboxViolationException.class, () -> guarded.execute(input("hello", "team")));
    verifyNoInteractions(adapter);
  }
}
