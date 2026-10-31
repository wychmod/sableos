package com.sableos.web.api;

import com.sableos.core.notify.NotifyChannelStore;
import com.sableos.core.notify.NotifyChannelView;
import com.sableos.web.api.dto.NotifyChannelRequest;
import com.sableos.web.api.dto.NotifyChannelResponse;
import com.sableos.web.common.ApiResponse;
import com.sableos.web.error.ConflictException;
import com.sableos.web.error.ResourceNotFoundException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局通知渠道注册表 CRUD（技术方案 §6.8；第 28 节补齐 19 节后置项）。
 *
 * <p>核心阶段只接受 {@code webhook} 类型；{@code url} 必须是 http/https。渠道名即全局唯一注册名，Agent 在正文按名引用。 新建重名
 * 409、更新/删除不存在 404、字段非法 400。
 */
@RestController
@RequestMapping("/api/v1/notify-channels")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段 Web 层无认证是既定前提（技术方案 §7.5）；本端点暴露面由部署方式兜底" + "（回环绑定或认证反向代理），端点级鉴权属扩展阶段。")
public class NotifyChannelApiController {

  private static final String WEBHOOK = "webhook";

  private static final String SCHEME_HTTP = "http";

  private static final String SCHEME_HTTPS = "https";

  private static final int MAX_NAME_LENGTH = 128;

  private static final int MAX_DESCRIPTION_LENGTH = 1000;

  private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

  private final NotifyChannelStore notifyChannelStore;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：构造器注入的注册表是依赖注入引用，本类无返回它的 getter，不构成暴露内部表示；"
              + "撤销条件：一旦新增暴露该字段的 getter 或 public 字段，须重新评估。")
  public NotifyChannelApiController(NotifyChannelStore notifyChannelStore) {
    this.notifyChannelStore = notifyChannelStore;
  }

  @GetMapping
  public ApiResponse<List<NotifyChannelResponse>> list() {
    return ApiResponse.ok(
        notifyChannelStore.list().stream().map(NotifyChannelResponse::from).toList());
  }

  @PostMapping
  public ApiResponse<NotifyChannelResponse> create(@RequestBody NotifyChannelRequest request) {
    String name = requireName(request == null ? null : request.name());
    validate(request);
    if (notifyChannelStore.find(name).isPresent()) {
      throw new ConflictException("通知渠道已存在: " + name);
    }
    Instant now = Instant.now();
    notifyChannelStore.save(toView(name, request, now));
    return ApiResponse.ok(
        notifyChannelStore
            .find(name)
            .map(NotifyChannelResponse::from)
            .orElseThrow(() -> new ResourceNotFoundException("通知渠道不存在: " + name)));
  }

  @PutMapping("/{name}")
  public ApiResponse<NotifyChannelResponse> update(
      @PathVariable String name, @RequestBody NotifyChannelRequest request) {
    String safeName = requireName(name);
    NotifyChannelView existing =
        notifyChannelStore
            .find(safeName)
            .orElseThrow(() -> new ResourceNotFoundException("通知渠道不存在: " + safeName));
    if (request == null) {
      throw new IllegalArgumentException("请求体不能为空");
    }
    if (request.name() != null
        && !request.name().isBlank()
        && !request.name().trim().equals(safeName)) {
      throw new IllegalArgumentException("路径与请求体中的 name 不一致");
    }
    validate(request);
    notifyChannelStore.save(toView(safeName, request, existing.createdAt()));
    return ApiResponse.ok(
        notifyChannelStore
            .find(safeName)
            .map(NotifyChannelResponse::from)
            .orElseThrow(() -> new ResourceNotFoundException("通知渠道不存在: " + safeName)));
  }

  @DeleteMapping("/{name}")
  public ApiResponse<Void> delete(@PathVariable String name) {
    String safeName = requireName(name);
    if (!notifyChannelStore.delete(safeName)) {
      throw new ResourceNotFoundException("通知渠道不存在: " + safeName);
    }
    return ApiResponse.ok(null);
  }

  private static NotifyChannelView toView(
      String name, NotifyChannelRequest request, Instant createdAt) {
    return new NotifyChannelView(
        name,
        asciiLowerCase(request.type().trim()),
        request.url().trim(),
        request.description(),
        request.config() == null ? Map.of() : Map.copyOf(request.config()),
        createdAt,
        Instant.now());
  }

  /**
   * 只对 ASCII 字母做小写映射：协议类型与 URL scheme 都是 ASCII；避免 Unicode case folding 带来的 confusable /
   * 长度变化问题（SpotBugs IMPROPER_UNICODE）。
   */
  private static String asciiLowerCase(String value) {
    StringBuilder lowered = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      lowered.append(current >= 'A' && current <= 'Z' ? (char) (current + ('a' - 'A')) : current);
    }
    return lowered.toString();
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不能为空");
    }
    String trimmed = name.trim();
    if (trimmed.length() > MAX_NAME_LENGTH || !NAME_PATTERN.matcher(trimmed).matches()) {
      throw new IllegalArgumentException("name 非法：1~128 位，仅允许字母、数字、点、下划线、短横线");
    }
    return trimmed;
  }

  private static void validate(NotifyChannelRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("请求体不能为空");
    }
    if (request.type() == null || !WEBHOOK.equals(asciiLowerCase(request.type().trim()))) {
      throw new IllegalArgumentException("核心阶段只支持 webhook 类型的通知渠道");
    }
    String url = request.url();
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("url 不能为空");
    }
    URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("url 非法: " + url);
    }
    String scheme = uri.getScheme() == null ? "" : asciiLowerCase(uri.getScheme());
    if (!SCHEME_HTTP.equals(scheme) && !SCHEME_HTTPS.equals(scheme)) {
      throw new IllegalArgumentException("url 必须是 http 或 https");
    }
    if (request.description() != null && request.description().length() > MAX_DESCRIPTION_LENGTH) {
      throw new IllegalArgumentException("description 超过 1000 字符");
    }
    if (request.config() != null) {
      for (Map.Entry<String, String> entry : request.config().entrySet()) {
        if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
          throw new IllegalArgumentException("config 的键值不能为空");
        }
      }
    }
  }
}
