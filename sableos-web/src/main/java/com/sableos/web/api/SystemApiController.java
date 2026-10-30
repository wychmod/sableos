package com.sableos.web.api;

import com.sableos.core.provider.ProviderHealthProbe;
import com.sableos.core.provider.ProviderStatus;
import com.sableos.web.api.dto.HealthView;
import com.sableos.web.api.dto.ProviderStatusView;
import com.sableos.web.api.dto.SystemInfoView;
import com.sableos.web.common.ApiResponse;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统状态端点（26 节）。
 *
 * <p>{@code /health} 刻意不碰数据库、模型与任何外部服务，只回答“进程还能处理请求”。{@code /info} 的提供方状态来自实时 HTTP 连通探测，单条失败不影响整体。
 */
@RestController
@RequestMapping("/api/v1")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段 Web 层无认证是既定前提（技术方案 §7.5）；本端点暴露面由部署方式兜底" + "（回环绑定或认证反向代理），端点级鉴权属扩展阶段。")
public class SystemApiController {

  private final ProviderHealthProbe providerHealthProbe;
  private final String appName;
  private final String appVersion;

  public SystemApiController(
      ProviderHealthProbe providerHealthProbe,
      @Value("${info.app.name:SableOS}") String appName,
      @Value("${info.app.version:unknown}") String appVersion) {
    this.providerHealthProbe = providerHealthProbe;
    this.appName = appName;
    this.appVersion = appVersion;
  }

  @GetMapping("/health")
  public ApiResponse<HealthView> health() {
    return ApiResponse.ok(new HealthView("ok"));
  }

  @GetMapping("/info")
  public ApiResponse<SystemInfoView> info() {
    List<ProviderStatusView> providers =
        providerHealthProbe.probe().stream().map(SystemApiController::toView).toList();
    return ApiResponse.ok(new SystemInfoView(appName, appVersion, providers));
  }

  private static ProviderStatusView toView(ProviderStatus status) {
    return new ProviderStatusView(
        status.name(), status.type(), status.reachable(), status.latencyMs(), status.error());
  }
}
