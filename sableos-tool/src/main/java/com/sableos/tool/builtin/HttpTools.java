package com.sableos.tool.builtin;

import com.sableos.tool.sandbox.ActionType;
import com.sableos.tool.sandbox.Sandbox;
import com.sableos.tool.sandbox.SandboxAction;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 内置 HTTP 工具：http_get / http_post。每个方法第一行过 {@code enforce(HTTP_REQUEST, url)}
 * 域名白名单——校验不过异常拦下，请求根本发不出去。
 *
 * <p>非 2xx 不特判、不自建错误页：RestClient 默认抛含状态码异常，由执行层收敛为失败结果（模型可自救）。
 */
public class HttpTools {

  private final Sandbox sandbox;
  private final RestClient restClient;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：RestClient 是无状态、线程安全的 HTTP 客户端抽象，构造注入即依赖注入本就必须持有协作者引用，"
              + "并非把可变内部表示暴露出去（本类无返回该字段的 getter，方法内只在栈上使用）。"
              + "撤销条件：一旦本类新增暴露 restClient 的 getter，或对其做原地可变配置，须重新评估并移除本抑制。")
  public HttpTools(Sandbox sandbox, RestClient restClient) {
    this.sandbox = sandbox;
    this.restClient = restClient;
  }

  @Tool(name = "http_get", description = "发起一个 HTTP GET 请求，返回响应体")
  public String httpGet(@ToolParam(description = "要请求的完整 URL") String url) {
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url));
    return restClient.get().uri(url).retrieve().body(String.class);
  }

  @Tool(name = "http_post", description = "发起一个 HTTP POST 请求（JSON body），返回响应体")
  public String httpPost(
      @ToolParam(description = "要请求的完整 URL") String url,
      @ToolParam(description = "JSON 请求体") String body) {
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url));
    return restClient
        .post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .body(String.class);
  }
}
