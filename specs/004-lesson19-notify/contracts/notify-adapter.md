# Contract: NotifyChannelAdapter（出站通知抽象，本节实现）

> 契约落 `sableos-tool`（`com.sableos.tool.notify`）——notify 三件与 `NotifyTools` 同模块（技术方案 §6.8 / AGENTS.md §7：`sableos-tool` 三合一）。本轮交付：**接口 + 通知目标 + 通用 webhook 实现**（research D-01/D-06）。

## 接口与实现

```java
package com.sableos.tool.notify;

public interface NotifyChannelAdapter {          // 签名与课件逐字一致
  void send(NotifyTarget target, String content);
}
```

```java
package com.sableos.tool.notify;

import java.util.Map;

public record NotifyTarget(String channelType, Map<String, String> config) {
  public NotifyTarget {
    config = (config == null) ? Map.of() : Map.copyOf(config);   // 防御拷贝（ProviderResponse 先例）
  }
}
```

```java
package com.sableos.tool.notify;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

public class WebhookNotifyAdapter implements NotifyChannelAdapter {   // 纯 POJO，装配归 20 节
  public WebhookNotifyAdapter(RestClient restClient) { ... }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("webhook 渠道缺少 url 配置（通知渠道注册项需要 url 键）");
    }
    restClient.post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .body(java.util.Map.of("content", content))
        .retrieve()
        .toBodilessEntity();
  }
}
```

## 行为契约

| 场景 | 契约 | 关联 |
| --- | --- | --- |
| 正常发送 | 向 `config["url"]` 发**恰好一次** `POST`；`Content-Type: application/json`；body `{"content":"<content>"}`；同步阻塞 | FR-002 / SC-001 |
| 地址来源 | 地址 100% 取自 `target.config()`——同一实现对不同配置发往不同地址；实现内零硬编码 | FR-002 / SC-003 |
| 缺 url（null/空白） | 抛 `IllegalArgumentException`，消息含 `url` 字面点名缺失键；**零请求发出** | 验收④ |
| 对端 4xx/5xx | 抛 `RestClientResponseException`（不 catch、不静默；不注册吞错处理器） | FR-002 / SC-002 |
| 连接失败/超时 | 抛 `ResourceAccessException`（同上） | FR-002 / SC-002 |
| 对端 3xx | 本节不特判（不声称已管控）；出网策略（禁跟随/逐跳复检/缺 Location 报错）归 23/24 节 | clarify #2 |
| 空 content | 照常发送（`{"content":""}`）——内容是否有意义由调用方判断 | Edge Cases |
| 审计 | 不在本层——失败经未来 `NotifyTools` 由 `ToolExecutor` 既有路径落 `tool_invocations`，不新增逻辑 | FR-002 |
| 白名单校验位 | 不在本层——`NotifyTools` 发出前接线（归 24 节）；适配器只负责投递语义 | FR-004 |
| 日志 | url/config 不进日志（地址可能内嵌凭证 token） | FR-005 |

## 接口中立性（SC-005 自查，人工项）

`send(NotifyTarget, String)` 签名中不出现 `webhook`/`url`/`feishu`/`email` 等任何一档实现特有的词。自查问题：**换成企业微信官方 SDK 实现（新 Adapter + 认证细节收在实现内），接口签名需要改吗？——不需要**。新增渠道 = 新增实现类（按 `channelType` 增挂），既有调用方零改动。

## 装配（归 20 节，本节只约定方向）

- 本节：纯 POJO，零 `@Component`、零 `@Configuration`。
- 20 节：`ToolRegistry` 收编时把 `WebhookNotifyAdapter` 装配为 bean（`RestClient` 由装配层提供），与 `NotifyTools` 一起接线；调用方只注入 `NotifyChannelAdapter` 契约类型。

## Harness 映射（第一批，本节判卷）

| 测试方法（英文） | @DisplayName（课件原文） | 守住的验收项 |
| --- | --- | --- |
| `sendPostsJsonBodyContainingContent` | 发送后收到一次 POST，body 里带 content | ①POST 恰好一次 + body 带 content 且按 `{"content": ...}` 包装 |
| `urlComesFromTargetConfigNotHardcoded` | 换通知目标只改配置零代码 | ②同一实现、不同配置 → 不同目标 |
| `serverErrorPropagatesAsException` | webhook 返回 5xx 异常向上抛、不静默吞掉 | ③5xx → `RestClientResponseException` |
| `missingUrlFailsFastWithoutRequest` | 缺 url 配置：报错点名且零请求发出 | ④IAE 含 `url` + 零请求 |

测试类：`sableos-tool/src/test/java/com/sableos/tool/notify/WebhookNotifyAdapterTest.java`；假 webhook = JDK 内置 `HttpServer`（research D-04）；直接 `new WebhookNotifyAdapter(RestClient.create())`，不起 Spring 上下文。

## 边界（本节不做）

- 不做专用渠道 API（企微/飞书/钉钉签名、AccessToken 刷新）——扩展阶段按 `channelType` 增挂实现。
- 不做重定向管控、不做域名白名单校验（23/24 节）。
- 不做重试/幂等键/异步——同步阻塞一次 POST 即全部语义。
