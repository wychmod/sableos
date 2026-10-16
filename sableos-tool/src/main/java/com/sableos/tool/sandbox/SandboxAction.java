package com.sableos.tool.sandbox;

/**
 * 待校验动作的描述（技术方案 §6.7）：类型 + 目标。
 *
 * @param type 动作类型（四值定死，见 {@link ActionType}）
 * @param target 动作目标：文件路径 / 可执行文件 / URL
 */
public record SandboxAction(ActionType type, String target) {}
