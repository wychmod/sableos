package com.sableos.web.api;

import com.sableos.tool.ToolRegistry;
import com.sableos.web.api.dto.ToolView;
import com.sableos.web.common.ApiResponse;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工具清单查询（26 节）：三条注册路径（直接实现 / 注解 / MCP）都已统一为同一形态。 */
@RestController
@RequestMapping("/api/v1/tools")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段 Web 层无认证是既定前提（技术方案 §7.5）；本端点暴露面由部署方式兜底" + "（回环绑定或认证反向代理），端点级鉴权属扩展阶段。")
public class ToolApiController {

  private final ToolRegistry toolRegistry;

  public ToolApiController(ToolRegistry toolRegistry) {
    this.toolRegistry = toolRegistry;
  }

  @GetMapping
  public ApiResponse<List<ToolView>> list() {
    return ApiResponse.ok(
        toolRegistry.all().stream()
            .map(tool -> new ToolView(tool.getName(), tool.getDescription(), tool.getInputSchema()))
            .toList());
  }
}
