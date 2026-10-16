package com.sableos.tool.interaction;

/**
 * 向人提问的抽象（human-in-the-loop 的落点）：接口先行，不绑定具体渠道。
 *
 * <p>CLI 场景读终端（{@link ConsoleUserInteraction}）；Web / 定时等无人值守场景装配 {@link
 * UnsupportedUserInteraction}——ask_user 明确报"当前渠道不支持"而非静默卡住。
 */
public interface UserInteraction {

  /** 向用户提问并拿回答；渠道无法交互时抛 {@link UnsupportedOperationException}（见无人值守实现）。 */
  String ask(String question);
}
