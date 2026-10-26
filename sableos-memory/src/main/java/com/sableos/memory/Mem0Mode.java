package com.sableos.memory;

/**
 * Mem0 的部署形态：决定 REST 路径与鉴权头。
 *
 * <ul>
 *   <li>{@link #PLATFORM}：Mem0 云（{@code api.mem0.ai}），路径带 {@code /v3}，鉴权 {@code Authorization:
 *       Token}。
 *   <li>{@link #OSS}：自托管 REST server，路径无版本前缀，鉴权 {@code X-API-Key}（关鉴权时可不填）。
 * </ul>
 *
 * <p>两者接口不同源（Platform v3 用 filters 分页，OSS 用树顶 user_id + 列表响应），因此必须显式区分， 不能只靠 base URL 猜。
 */
public enum Mem0Mode {
  PLATFORM,
  OSS;

  private static final String PLATFORM_VALUE = "platform";
  private static final String OSS_VALUE = "oss";

  /** 配置值解析（区分大小写，仅接受文档里的小写值）；缺省按 Platform。 */
  public static Mem0Mode from(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || PLATFORM_VALUE.equals(normalized)) {
      return PLATFORM;
    }
    if (OSS_VALUE.equals(normalized)) {
      return OSS;
    }
    throw new IllegalArgumentException(
        "memory.mem0.mode 只支持 " + PLATFORM_VALUE + " 或 " + OSS_VALUE + "，收到: " + value);
  }
}
