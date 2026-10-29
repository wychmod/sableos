package com.sableos.tool.sandbox;

import com.sableos.core.sandbox.WhitelistType;
import com.sableos.core.sandbox.WhitelistValidationException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 三类白名单值的校验与归一（specs/008 §6.3）。
 *
 * <p><b>为什么规则放 tool 而不在 core</b>：校验规则必须与运行时的匹配语义同源——{@link WhitelistSandbox} 用 {@code
 * Path.normalize()} 比路径、按首 token 精确比命令、按通配比域名。规则若另置一处，存储形态和匹配形态迟早漂移，表现为"存进去了却永远不命中"。
 *
 * <p><b>归一必须幂等</b>：{@code normalize(normalize(x)) == normalize(x)}。归一后的形态同时是存储形态、唯一性判重形态与 API
 * 返回形态，三者不一致就会出现"库里两条、语义一条"。
 *
 * <p>校验失败一律抛 {@link WhitelistValidationException}，message 只描述字段与规则、**不含原始输入**（避免回显到响应体）。
 */
public final class WhitelistRules {

  /** 值的最大长度，与表列定义一致。 */
  static final int MAX_VALUE_LENGTH = 1024;

  private static final String WILDCARD_PREFIX = "*.";

  /** 通配符字符本身，用于识别"含星号但不是合法前缀"的输入。 */
  private static final String WILDCARD_CHAR = "*";

  /** {@code Path.normalize()} 对 "." 的结果，归一后视为空路径。 */
  private static final String CURRENT_DIR = ".";

  /** DNS 标签分隔符。 */
  private static final String LABEL_SEPARATOR = ".";

  /** 正则形态的标签分隔符（转义点号）。 */
  private static final String LABEL_SEPARATOR_REGEX = "\\.";

  /** {@code split} 的 limit：保留末尾空串，才能识别 {@code example.com.} 这种尾点写法。 */
  private static final int KEEP_TRAILING_EMPTY = -1;

  private static final char NUL = '\0';
  private static final char CR = '\r';
  private static final char LF = '\n';

  /** 单标签允许的最大长度（DNS 规定 63，超出必然非法）。 */
  private static final int MAX_LABEL_LENGTH = 63;

  /** 十进制基数式判断用的字符（仅 ASCII 小写字母与数字）。 */
  private static final char ASCII_LOWER_A = 'a';

  private static final char ASCII_LOWER_Z = 'z';
  private static final char ASCII_DIGIT_0 = '0';
  private static final char ASCII_DIGIT_9 = '9';
  private static final char HYPHEN = '-';

  private WhitelistRules() {}

  /**
   * 按类型校验并归一。
   *
   * @param type 类型
   * @param raw 原始输入
   * @return 归一后的值
   * @throws WhitelistValidationException 不合规
   */
  public static String normalize(WhitelistType type, String raw) {
    if (type == null) {
      throw new WhitelistValidationException("type 不能为空");
    }
    if (raw == null) {
      throw new WhitelistValidationException("value 不能为空");
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      throw new WhitelistValidationException("value 不能为空");
    }
    return switch (type) {
      case FILE_PATH -> normalizeFilePath(trimmed);
      case SHELL_COMMAND -> normalizeShellCommand(trimmed);
      case HTTP_DOMAIN -> normalizeHttpDomain(trimmed);
    };
  }

  /**
   * 文件路径：语法合法、无控制字符、归一后非空；分隔符统一成 {@code /}。
   *
   * <p>统一分隔符是跨平台存储的关键：{@code Path.normalize().toString()} 在 Windows 上产出反斜杠，直接入库后同一份 SQLite 在 POSIX
   * 上会被解析成"名为 {@code .sableos\agents} 的单个文件"，白名单静默失效。
   *
   * <p>盘符大小写刻意不归一：强制大写会在 POSIX 上错误改写合法文件名，而实际使用中盘符大小写几乎总是一致。
   */
  private static String normalizeFilePath(String value) {
    requireLength(value);
    requireNoControlChars(value, "FILE_PATH");
    Path normalized;
    try {
      normalized = Path.of(value).normalize();
    } catch (InvalidPathException e) {
      throw new WhitelistValidationException("FILE_PATH 不是合法的路径");
    }
    String text = normalized.toString().replace('\\', '/');
    if (text.isEmpty() || CURRENT_DIR.equals(text)) {
      throw new WhitelistValidationException("FILE_PATH 归一后为空");
    }
    return text;
  }

  /** Shell 命令：单 token（不含空白），大小写敏感原样保留。 */
  private static String normalizeShellCommand(String value) {
    requireLength(value);
    requireNoControlChars(value, "SHELL_COMMAND");
    for (int i = 0; i < value.length(); i++) {
      if (Character.isWhitespace(value.charAt(i))) {
        throw new WhitelistValidationException("SHELL_COMMAND 必须是单个可执行文件名，不能含空白");
      }
    }
    return value;
  }

  /**
   * HTTP 域名：主机名或 {@code *.主机名}，仅 ASCII，统一小写。
   *
   * <p>拒绝非 ASCII 是刻意的（AGENTS.md 第 10 章）：{@code URI.getHost()} 对非 ASCII 主机名返回 {@code
   * null}，校验时会被判成"缺少主机名"，存进去永远不生效，且拒绝理由会误导排障方向。
   */
  private static String normalizeHttpDomain(String value) {
    requireLength(value);
    requireNoControlChars(value, "HTTP_DOMAIN");
    requireAscii(value);

    String lower = value.toLowerCase(Locale.ROOT);
    String host = lower;
    if (lower.startsWith(WILDCARD_PREFIX)) {
      host = lower.substring(WILDCARD_PREFIX.length());
      if (host.isEmpty()) {
        throw new WhitelistValidationException("HTTP_DOMAIN 通配符后缺少主机名");
      }
    }
    if (lower.contains(WILDCARD_CHAR) && !lower.startsWith(WILDCARD_PREFIX)) {
      throw new WhitelistValidationException("HTTP_DOMAIN 通配符只能出现在最左侧且必须是 *. 前缀");
    }
    validateHost(host);
    return lower;
  }

  private static void validateHost(String host) {
    if (host.startsWith(LABEL_SEPARATOR) || host.endsWith(LABEL_SEPARATOR)) {
      throw new WhitelistValidationException("HTTP_DOMAIN 不能以点号开头或结尾");
    }
    if (host.contains(LABEL_SEPARATOR + LABEL_SEPARATOR)) {
      throw new WhitelistValidationException("HTTP_DOMAIN 不能包含连续的点号");
    }
    // 含 scheme / 端口 / 路径 / 查询串的输入在这里被拆成不合法标签
    for (String label : host.split(LABEL_SEPARATOR_REGEX, KEEP_TRAILING_EMPTY)) {
      if (!isValidLabel(label)) {
        throw new WhitelistValidationException("HTTP_DOMAIN 格式非法：只接受主机名或 *.主机名，不含协议、端口、路径");
      }
    }
  }

  /**
   * 标签是否合法：非空、≤63、首尾为字母或数字、中间允许连字符。
   *
   * <p>手写字符扫描而非正则：{@code [a-z0-9]([a-z0-9-]*[a-z0-9])?} 在"{@code a-}*N"输入上有回溯退化（SpotBugs
   * REDOS），而这是对外可达的校验入口。
   */
  private static boolean isValidLabel(String label) {
    if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
      return false;
    }
    for (int i = 0; i < label.length(); i++) {
      char c = label.charAt(i);
      boolean alnum = isAsciiAlnum(c);
      if (!alnum && c != HYPHEN) {
        return false;
      }
      if (i == 0 || i == label.length() - 1) {
        if (!alnum) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean isAsciiAlnum(char c) {
    return (c >= ASCII_LOWER_A && c <= ASCII_LOWER_Z) || (c >= ASCII_DIGIT_0 && c <= ASCII_DIGIT_9);
  }

  private static void requireLength(String value) {
    if (value.length() > MAX_VALUE_LENGTH) {
      throw new WhitelistValidationException("value 长度不能超过 " + MAX_VALUE_LENGTH);
    }
  }

  private static void requireNoControlChars(String value, String type) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == NUL || c == CR || c == LF) {
        throw new WhitelistValidationException(type + " 不能包含控制字符");
      }
    }
  }

  private static void requireAscii(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) > 0x7F) {
        throw new WhitelistValidationException("HTTP_DOMAIN 只接受 ASCII 主机名，请使用 punycode（xn--...）形式");
      }
    }
  }
}
