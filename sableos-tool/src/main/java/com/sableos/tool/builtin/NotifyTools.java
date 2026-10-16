package com.sableos.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.sableos.core.profile.ProfileContext;
import com.sableos.core.tool.SableTool;
import com.sableos.core.tool.ToolResult;
import com.sableos.tool.notify.NotifyChannelAdapter;
import com.sableos.tool.notify.NotifyTarget;
import com.sableos.tool.sandbox.ActionType;
import com.sableos.tool.sandbox.Sandbox;
import com.sableos.tool.sandbox.SandboxAction;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * notify 工具（19 节登记契约的兑现，本节实现、完整 harness 24 节后补跑）：把一条内容推到已注册的通知渠道。
 *
 * <p>直接实现 {@link SableTool}（非 {@code @Tool} 注解来源）。执行顺序即安全不变量：缺 content / 无上下文 / 渠道未命中都在 {@code
 * enforce} 与 {@code send} 之前点名失败、零请求；解析成功后先 {@code enforce(HTTP_REQUEST, url)} 再 {@code
 * adapter.send}——enforce 早于 send 是漏洞线（{@code InOrder} 钉死归 24 节后）。
 *
 * <p>审计不建旁路：成败经 {@code ToolExecutor} 既有路径落 {@code tool_invocations}。渠道解析严沿 19 节：缺省 / 空白 / 字面量
 * {@code default} 取注册表第一个；非空按名查、未命中不回退。
 */
public class NotifyTools implements SableTool {

  private static final String NAME = "notify";

  private static final String DESCRIPTION = "向已注册的通知渠道推送一条消息（把结果或告警送到群 / webhook）";

  private static final String INPUT_SCHEMA =
      "{\"type\":\"object\",\"properties\":{"
          + "\"content\":{\"type\":\"string\",\"description\":\"要推送的消息内容\"},"
          + "\"channel\":{\"type\":\"string\",\"description\":\"目标渠道名（缺省 / default 取第一个已注册渠道）\"}"
          + "},\"required\":[\"content\"]}";

  private static final String DEFAULT_CHANNEL = "default";

  private final Sandbox sandbox;
  private final NotifyChannelAdapter adapter;
  private final Map<String, NotifyTarget> channels;

  public NotifyTools(
      Sandbox sandbox, NotifyChannelAdapter adapter, Map<String, NotifyTarget> channels) {
    this.sandbox = sandbox;
    this.adapter = adapter;
    // 保序：缺省取「第一个」依赖插入顺序，Map.copyOf 不保证顺序，故用 LinkedHashMap 包一层
    this.channels = Collections.unmodifiableMap(new LinkedHashMap<>(channels));
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return DESCRIPTION;
  }

  @Override
  public String getInputSchema() {
    return INPUT_SCHEMA;
  }

  @Override
  public ToolResult execute(JsonNode input) {
    String content = input == null ? null : textual(input, "content");
    if (content == null || content.isBlank()) {
      return ToolResult.failure("notify 缺少必填参数 content", false);
    }
    if (ProfileContext.current() == null) {
      return ToolResult.failure("当前无 Agent 上下文，无法确定通知归属，notify 拒绝发送", false);
    }
    String channel = textual(input, "channel");
    NotifyTarget target = resolve(channel);
    if (target == null) {
      return ToolResult.failure(channelFailureMessage(channel), false);
    }
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, target.config().get("url")));
    adapter.send(target, content);
    return ToolResult.success("已推送");
  }

  /** 缺省 / 空白 / {@code default} → 第一个；非空按名查、未命中不回退（返 null 交上层点名失败）。 */
  private NotifyTarget resolve(String channel) {
    if (channel == null || channel.isBlank() || DEFAULT_CHANNEL.equals(channel)) {
      return channels.values().stream().findFirst().orElse(null);
    }
    return channels.get(channel);
  }

  private String channelFailureMessage(String channel) {
    if (channels.isEmpty()) {
      return "无已注册的通知渠道，notify 无法发送";
    }
    return "未找到通知渠道: " + channel;
  }

  private static String textual(JsonNode input, String field) {
    JsonNode node = input.path(field);
    return node.isTextual() ? node.asText() : null;
  }
}
