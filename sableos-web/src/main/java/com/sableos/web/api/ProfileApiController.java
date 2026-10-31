package com.sableos.web.api;

import com.sableos.core.profile.Profile;
import com.sableos.core.profile.ProfileRegistry;
import com.sableos.web.api.dto.ProfileView;
import com.sableos.web.common.ApiResponse;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 清单查询（26 节）：只暴露可公开字段，系统提示词等内部指令不出现。 */
@RestController
@RequestMapping("/api/v1/profiles")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段 Web 层无认证是既定前提（技术方案 §7.5）；本端点暴露面由部署方式兜底" + "（回环绑定或认证反向代理），端点级鉴权属扩展阶段。")
public class ProfileApiController {

  private final ProfileRegistry profileRegistry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "确认误报：ProfileRegistry 自第 29 节起支持运行期 remove，SpotBugs 因而把它判为可变类型；"
              + "本类只把它当依赖注入的协作者查询使用，无返回它的 getter，也不暴露其内部集合。"
              + "撤销条件：一旦新增暴露该字段的 getter 或 public 字段，须重新评估。")
  public ProfileApiController(ProfileRegistry profileRegistry) {
    this.profileRegistry = profileRegistry;
  }

  @GetMapping
  public ApiResponse<List<ProfileView>> list() {
    return ApiResponse.ok(
        profileRegistry.all().stream().map(ProfileApiController::toView).toList());
  }

  private static ProfileView toView(Profile profile) {
    Profile.ProviderRef provider = profile.provider();
    return new ProfileView(
        profile.name(),
        profile.description(),
        provider == null ? null : provider.name(),
        provider == null ? null : provider.model(),
        profile.tools(),
        profile.channels());
  }
}
