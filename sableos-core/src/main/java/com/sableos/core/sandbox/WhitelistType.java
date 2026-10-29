package com.sableos.core.sandbox;

/**
 * 白名单条目的类型（技术方案 §6.7）。
 *
 * <p>与 {@code ActionType} 的对应关系：{@code FILE_PATH} 覆盖 {@code FILE_READ}/{@code FILE_WRITE}，{@code
 * SHELL_COMMAND} 对应 {@code SHELL_COMMAND}，{@code HTTP_DOMAIN} 对应 {@code HTTP_REQUEST}。
 *
 * <p>不含 SMTP：核心阶段没有 SMTP 动作，存了也不会被执行（AGENTS.md 第 10 章）；待 {@code EmailNotifyAdapter} 落地时再加。
 */
public enum WhitelistType {
  FILE_PATH,
  SHELL_COMMAND,
  HTTP_DOMAIN
}
