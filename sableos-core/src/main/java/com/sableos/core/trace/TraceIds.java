package com.sableos.core.trace;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.ThreadLocalRandom;

/**
 * W3C trace-context 标识的生成与校验。
 *
 * <p>刻意不用 {@link java.util.UUID#randomUUID()}：它走共享静态 {@code SecureRandom}，在高并发虚拟线程下是竞争点（已用 javap
 * 核验其字节码）。trace-id 不是凭证而是关联标识，用 {@link ThreadLocalRandom} 生成 128 位随机数即可满足 W3C 对长度与随机性的要求。
 */
public final class TraceIds {

  /** W3C trace-id 长度：16 字节 = 32 位小写 hex。 */
  public static final int TRACE_ID_LENGTH = 32;

  /** W3C span-id（traceparent 中称 parent-id）长度：8 字节 = 16 位小写 hex。 */
  public static final int SPAN_ID_LENGTH = 16;

  private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
  private static final int HEX_DIGITS_PER_LONG = 16;
  private static final int BITS_PER_HEX_DIGIT = 4;
  private static final long NIBBLE_MASK = 0xFL;
  private static final char ZERO = '0';
  private static final char NINE = '9';
  private static final char LOWER_A = 'a';
  private static final char LOWER_F = 'f';

  private TraceIds() {}

  /** 生成新的 trace-id：32 位小写 hex，保证非全零（全零在 W3C 中是非法值）。 */
  @SuppressFBWarnings(
      value = "PREDICTABLE_RANDOM",
      justification =
          "trace-id 是关联标识而非安全凭证：HTTP 入口本就接受客户端提供的 traceparent / X-Trace-Id，"
              + "不可预测性不构成任何安全边界。反之 SecureRandom.nextBytes 在 synchronized 内做阻塞 IO，"
              + "在 JDK 21 虚拟线程上会钉住载体线程（AGENTS.md §5.2），而本方法位于每个请求的热路径。"
              + "撤销条件：一旦新增「按 traceId 查询」的对外端点，必须改用安全随机源。")
  public static String newTraceId() {
    long high;
    long low;
    do {
      high = ThreadLocalRandom.current().nextLong();
      low = ThreadLocalRandom.current().nextLong();
    } while ((high | low) == 0L);
    return toHex(high) + toHex(low);
  }

  /** 生成新的 span-id：16 位小写 hex，保证非零。 */
  @SuppressFBWarnings(
      value = "PREDICTABLE_RANDOM",
      justification = "同 newTraceId：span-id 亦为关联标识，非安全凭证，且不可走 SecureRandom 的阻塞路径。")
  public static String newSpanId() {
    long value;
    do {
      value = ThreadLocalRandom.current().nextLong();
    } while (value == 0L);
    return toHex(value);
  }

  /** 是否为合法 trace-id：长度 32、全小写 hex、非全零。 */
  public static boolean isValidTraceId(String value) {
    return isLowerHex(value, TRACE_ID_LENGTH) && !isAllZero(value);
  }

  /** 是否为合法 span-id：长度 16、全小写 hex、非全零。 */
  public static boolean isValidSpanId(String value) {
    return isLowerHex(value, SPAN_ID_LENGTH) && !isAllZero(value);
  }

  /** 是否为指定长度的小写 hex 串（不校验全零，供 version / flags 等短字段复用）。 */
  public static boolean isLowerHex(String value, int expectedLength) {
    if (value == null || value.length() != expectedLength) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean isDigit = c >= ZERO && c <= NINE;
      boolean isLowerHexLetter = c >= LOWER_A && c <= LOWER_F;
      if (!isDigit && !isLowerHexLetter) {
        return false;
      }
    }
    return true;
  }

  private static boolean isAllZero(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) != ZERO) {
        return false;
      }
    }
    return true;
  }

  private static String toHex(long value) {
    char[] out = new char[HEX_DIGITS_PER_LONG];
    long remaining = value;
    for (int i = HEX_DIGITS_PER_LONG - 1; i >= 0; i--) {
      out[i] = HEX_DIGITS[(int) (remaining & NIBBLE_MASK)];
      remaining >>>= BITS_PER_HEX_DIGIT;
    }
    return new String(out);
  }
}
