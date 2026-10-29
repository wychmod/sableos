package com.sableos.core.sandbox;

/**
 * 写入白名单的意图（新增或更新的入参）。
 *
 * <p>{@code value} 是**原始输入**，归一由 {@link SandboxWhitelistAdmin} 的实现层负责——core
 * 只表达"要写什么"，不承载校验规则（规则依赖运行时的匹配语义，见 specs/008 的 research D-06）。
 *
 * @param type 类型
 * @param value 原始输入值
 * @param description 可选说明
 */
public record WhitelistCommand(WhitelistType type, String value, String description) {}
