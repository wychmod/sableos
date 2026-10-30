package com.sableos.web.api;

/**
 * 对外接口的固定口径（26 节）：渠道、默认用户、请求大小与历史条数上限。
 *
 * <p>集中一处是为了让 32KB / 100 条 / {@code web} / {@code anonymous} 只有一个来源；散落各 Controller 时最容易出现“发消息限了
 * 32KB、invoke 没限”这类第二口径。
 */
final class ApiConstraints {

  /** Web 入口的渠道标识（与 {@code TraceContext.CHANNEL_WEB} 同值）。 */
  static final String CHANNEL_WEB = "web";

  /** 未提供用户标识时的缺省值。 */
  static final String DEFAULT_USER = "anonymous";

  /** 单条消息上限（字符数，与课件 `content.length() > 32 * 1024` 口径一致）。 */
  static final int MAX_MESSAGE_CHARS = 32 * 1024;

  /** 查历史时最多返回的最近条数。 */
  static final int MAX_HISTORY_MESSAGES = 100;

  private ApiConstraints() {}

  /** 校验必填文本，返回原值；空白与超长统一抛 400 可定位原因。 */
  static String requireMessage(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    if (value.length() > MAX_MESSAGE_CHARS) {
      throw new IllegalArgumentException(field + " 超过 32KB 上限");
    }
    return value;
  }
}
