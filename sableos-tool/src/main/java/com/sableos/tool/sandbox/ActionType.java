package com.sableos.tool.sandbox;

/**
 * 动作类型四值——本节定死，24 节 {@code WhitelistSandbox} 直接沿用，不增不改。
 *
 * <p>与 {@code SandboxAction.target} 的对应：文件读写 → 路径；Shell 命令 → 可执行文件；HTTP 请求 → URL。
 */
public enum ActionType {
  /** 文件读（目标为路径）。 */
  FILE_READ,
  /** 文件写（目标为路径）。 */
  FILE_WRITE,
  /** Shell 命令（目标为可执行文件）。 */
  SHELL_COMMAND,
  /** HTTP 请求（目标为 URL）。 */
  HTTP_REQUEST
}
