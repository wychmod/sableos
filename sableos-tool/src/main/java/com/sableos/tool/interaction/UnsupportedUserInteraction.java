package com.sableos.tool.interaction;

/**
 * 无人值守渠道（定时任务等）的 {@link UserInteraction} 实现：不支持向用户提问，被问即大声失败。
 *
 * <p>绝不静默卡住等回答——定时任务没有终端可读，静默等待会让 Agent 永久挂起。
 */
public class UnsupportedUserInteraction implements UserInteraction {

  @Override
  public String ask(String question) {
    throw new UnsupportedOperationException("当前渠道不支持向用户提问（无人值守渠道）");
  }
}
