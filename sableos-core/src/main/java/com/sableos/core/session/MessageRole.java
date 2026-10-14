package com.sableos.core.session;

/** 会话历史条目的角色（技术方案 §4；口径见 specs/002-lesson17-react/data-model.md §2）。 */
public enum MessageRole {
  /** 用户输入。 */
  USER,
  /** 模型输出。 */
  ASSISTANT,
  /** 工具执行结果。 */
  TOOL
}
