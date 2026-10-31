package com.sableos.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.sableos.core.notify.NotifyChannelStore;
import com.sableos.core.notify.NotifyChannelView;
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
 * notify 工具（19 节登记契约的兑现，24 节补跑 harness；28 节接全局注册表）：把一条内容推到已注册的通知渠道。
 *
 * <p>直接实现 {@link SableTool}（非 {@code @Tool} 注解来源）。执行顺序即安全不变量：缺 content / 无上下文 / 渠道未命中都在 {@code
 * enforce} 与 {@code send} 之前点名失败、零请求；解析成功后先 {@code enforce(HTTP_REQUEST, url)} 再 {@code
 * adapter.send}——enforce 早于 send 是漏洞线（{@code InOrder} 钉死）。
 *
 * <p>28 节起生产装配使用 {@link NotifyChannelStore}：每次执行现查注册表，缺省 / 空白 / 字面量 {@code default} 取第一个已登记渠道；
 * 非空按名查、未命中不回退。保留 Map 构造器供 19 节既有单测使用，测试焦点不漂移。
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
  private final NotifyChannelStore channelStore;

  /** 19 节既有单测用：内存渠道表。 */
  public NotifyTools(
      Sandbox sandbox, NotifyChannelAdapter adapter, Map<String, NotifyTarget> channels) {
    this(sandbox, adapter, channels, null);
  }

  /** 28 节生产装配用：每次执行从持久化注册表解析渠道。 */
  public NotifyTools(
      Sandbox sandbox, NotifyChannelAdapter adapter, NotifyChannelStore channelStore) {
    this(sandbox, adapter, Map.of(), channelStore);
  }

  private NotifyTools(
      Sandbox sandbox,
      NotifyChannelAdapter adapter,
      Map<String, NotifyTarget> channels,
      NotifyChannelStore channelStore) {
    this.sandbox = sandbox;
    this.adapter = adapter;
    // 保序：缺省取「第一个」依赖插入顺序，Map.copyOf 不保证顺序，故用 LinkedHashMap 包一层
    this.channels = Collections.unmodifiableMap(new LinkedHashMap<>(channels));
    this.channelStore = channelStore;
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
    if (channelStore != null) {
      return resolveFromStore(channel);
    }
    if (channel == null || channel.isBlank() || DEFAULT_CHANNEL.equals(channel)) {
      return channels.values().stream().findFirst().orElse(null);
    }
    return channels.get(channel);
  }

  private NotifyTarget resolveFromStore(String channel) {
    if (channel == null || channel.isBlank() || DEFAULT_CHANNEL.equals(channel)) {
      return channelStore.list().stream().findFirst().map(NotifyTools::toTarget).orElse(null);
    }
    return channelStore.find(channel).map(NotifyTools::toTarget).orElse(null);
  }

  private static NotifyTarget toTarget(NotifyChannelView channel) {
    Map<String, String> config = new LinkedHashMap<>(channel.config());
    // url 是 webhook 的权威地址；覆盖写入 config，复用既有 WebhookNotifyAdapter。
    config.put("url", channel.url());
    return new NotifyTarget(channel.type(), config);
  }

  private String channelFailureMessage(String channel) {
    boolean empty = (channelStore != null) ? channelStore.list().isEmpty() : channels.isEmpty();
    if (empty) {
      return "无已注册的通知渠道，notify 无法发送";
    }
    return "未找到通知渠道: " + channel;
  }

  private static String textual(JsonNode input, String field) {
    JsonNode node = input.path(field);
    return node.isTextual() ? node.asText() : null;
  }
}
