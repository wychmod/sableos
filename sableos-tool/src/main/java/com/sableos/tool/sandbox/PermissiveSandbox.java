package com.sableos.tool.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 临时放行装配（24 节替换为 {@code WhitelistSandbox}）：放行一切、但每次放行记 WARN 留痕——绝不静默裸奔（spec 边界）。
 *
 * <p>target 为用户可控字符串：入日志前 CRLF 净化（形态与 AGENTS.md §5.2 一致，勿改）。
 */
public class PermissiveSandbox implements Sandbox {

  private static final Logger LOG = LoggerFactory.getLogger(PermissiveSandbox.class);

  @Override
  public void enforce(SandboxAction action) {
    LOG.warn(
        "PermissiveSandbox 放行（24 节白名单实现替换）: type={}, target={}",
        safe(String.valueOf(action.type())),
        safe(action.target()));
  }

  /** CRLF 净化（保持 replaceAll 字面形态，findsecbugs 只认这种写法，勿改用 Pattern/Matcher 或双反斜杠）。 */
  private static String safe(String value) {
    return value == null ? "" : value.replaceAll("[\r\n]", "_");
  }
}
