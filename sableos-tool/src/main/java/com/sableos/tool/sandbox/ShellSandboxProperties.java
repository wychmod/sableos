package com.sableos.tool.sandbox;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shell 白名单（技术方案 §6.7）：允许执行的可执行文件首 token。
 *
 * <p>空列表 = 该类动作全部拒绝（deny-all）。比对精确、大小写敏感；解释器仅在管理员显式列入时允许。 null 与空白条目会被跳过；防御性拷贝为不可变列表，避免 SpotBugs
 * {@code EI_EXPOSE_REP}（对齐 {@code ProvidersProperties} 先例）。
 *
 * @param allowedCommands 允许执行的可执行文件首 token
 */
@ConfigurationProperties(prefix = "shell")
public record ShellSandboxProperties(List<String> allowedCommands) {

  public ShellSandboxProperties {
    allowedCommands =
        (allowedCommands == null)
            ? List.of()
            : List.copyOf(allowedCommands.stream().filter(Objects::nonNull).toList());
  }
}
