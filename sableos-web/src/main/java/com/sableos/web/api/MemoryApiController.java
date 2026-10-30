package com.sableos.web.api;

import com.sableos.core.memory.MemoryProvider;
import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.web.api.dto.MemoryView;
import com.sableos.web.common.ApiResponse;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 长期记忆全文查询（26 节）。
 *
 * <p>{@code profile} 缺省 {@code default}；未注册的 Agent 或尚未写入记忆都返回空内容而不是错误——干净环境里“没写过”不等于“资源不存在”。
 * 只读：写入仍由 Agent 的 {@code save_memory} 工具完成。
 */
@RestController
@RequestMapping("/api/v1/memory")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段 Web 层无认证是既定前提（技术方案 §7.5）；本端点暴露面由部署方式兜底" + "（回环绑定或认证反向代理），端点级鉴权属扩展阶段。")
public class MemoryApiController {

  private static final String DEFAULT_PROFILE = "default";

  private final ProfileRegistry profileRegistry;
  private final MemoryProvider memoryProvider;

  public MemoryApiController(ProfileRegistry profileRegistry, MemoryProvider memoryProvider) {
    this.profileRegistry = profileRegistry;
    this.memoryProvider = memoryProvider;
  }

  @GetMapping
  public ApiResponse<MemoryView> get(
      @RequestParam(name = "profile", required = false) String profile) {
    String name = (profile == null || profile.isBlank()) ? DEFAULT_PROFILE : profile;
    Profile registered = profileRegistry.get(name);
    String content = (registered == null) ? "" : memoryProvider.memorySection(registered);
    return ApiResponse.ok(new MemoryView(name, content == null ? "" : content));
  }
}
