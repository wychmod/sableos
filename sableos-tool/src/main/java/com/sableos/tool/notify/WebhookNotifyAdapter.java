package com.sableos.tool.notify;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 核心阶段唯一一档实现（FR-002）：把内容包成 {@code {"content": ...}} 向 NotifyTarget.config 的 {@code url} 发一次
 * POST——地址只来自通知目标配置，实现内零硬编码。
 *
 * <p>失败语义：缺 url 立即抛 {@link IllegalArgumentException}（点名 url、零请求）；对端 4xx/5xx 与连接失败 由 RestClient
 * 默认判定异常上抛，绝不静默吞掉。3xx 不特判（出网管控归 23/24 节 Sandbox）。 url 与 config 不进日志（FR-005）。
 */
public class WebhookNotifyAdapter implements NotifyChannelAdapter {

  private final RestClient restClient;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：RestClient 是无状态、线程安全的 HTTP 客户端抽象（接口），构造注入即依赖注入本就必须持有协作者引用，"
              + "并非把可变内部表示暴露出去（本类无返回该字段的 getter，send 只在方法栈内使用）。"
              + "依赖注入本就必须持有协作者引用，不引入多余抽象接口即可规避 EI_EXPOSE_REP2 属 SpotBugs 对"
              + "「注入具体协作者」的固有误判。撤销条件：一旦本类新增暴露 restClient 的 getter，"
              + "或对其做原地可变配置（如中途替换 baseUrl/拦截器），须重新评估并移除本抑制。")
  public WebhookNotifyAdapter(RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("webhook 渠道缺少 url 配置（通知渠道注册项需要 url 键）");
    }
    restClient
        .post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("content", content))
        .retrieve()
        .toBodilessEntity();
  }
}
