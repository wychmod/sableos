package com.sableos.core.tool;

import com.sableos.core.profile.Profile;
import java.util.List;

/**
 * 工具集合的前向契约（依赖倒置）：按 Profile 过滤与按名解析（技术方案 §6.2）。
 *
 * <p>真实现（{@code ToolRegistry} 与三档接入）归 20 节 {@code sableos-tool}；本节 core 内定义契约，boot 装配空实现
 * （无工具环境下模型要求调工具按「未注册」失败处理）。
 */
public interface ToolCatalog {

  /** 本次 Profile 可用的工具（按 Profile 声明的工具集过滤）；空列表合法（无工具）。 */
  List<SableTool> toolsFor(Profile profile);

  /** 按工具名解析；未解析到返回 null（由 {@code ToolExecutor} 转「未注册」失败结果）。 */
  SableTool find(String name);
}
