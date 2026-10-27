package com.sableos.tool.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 测试替身：放行一切，供不关心校验的用例构造工具；生产装配只使用 {@link WhitelistSandbox}。
 *
 * <p>每次放行记 WARN 留痕——绝不静默裸奔。target 为用户可控字符串：入日志前 CRLF 净化（形态与 AGENTS.md §5.2 一致，勿改）。
 */
public class PermissiveSandbox implements Sandbox {

  private static final Logger LOG = LoggerFactory.getLogger(PermissiveSandbox.class);

  @Override
  public void enforce(SandboxAction action) {
    LOG.warn(
        "PermissiveSandbox 测试替身放行: type={}, target={}",
        safe(String.valueOf(action.type())),
        safe(action.target()));
  }

  /** CRLF 净化（保持 replaceAll 字面形态，findsecbugs 只认这种写法，勿改用 Pattern/Matcher 或双反斜杠）。 */
  private static String safe(String value) {
    return value == null ? "" : value.replaceAll("[\r\n]", "_");
  }
}
