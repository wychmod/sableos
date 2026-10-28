package com.sableos.tool.sandbox;

/**
 * 安全校验接口（技术方案 §6.7，接口先行）：工具执行涉外动作（文件 / Shell / HTTP）前先过校验——校验不过抛 {@link
 * SandboxViolationException}，外部动作零发生。
 *
 * <p>生产实现为 {@link WhitelistSandbox}（应用层白名单，空配置即全部拒绝）；放行替身只存在于测试源码，不在生产 classpath 上。禁用 {@code
 * SecurityManager}（JDK 17 起废弃、21 已不可用）。
 */
public interface Sandbox {

  /**
   * 校验一个拟执行的动作；不允许则抛 {@link SandboxViolationException}。
   *
   * @param action 动作描述（类型 + 目标）
   */
  void enforce(SandboxAction action);
}
