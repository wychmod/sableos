package com.sableos.tool.sandbox;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件白名单（技术方案 §6.7）：允许读写的路径根。
 *
 * <p>空列表 = 该类动作全部拒绝（deny-all），不是"不校验"。相对路径按 JVM 当前工作目录解析； null 条目在构造时跳过，空白条目由实现层跳过。防御性拷贝为不可变列表，避免
 * SpotBugs {@code EI_EXPOSE_REP}（对齐 {@code ProvidersProperties} 先例）。
 *
 * @param allowedPaths 允许读写的目录或文件根
 */
@ConfigurationProperties(prefix = "file")
public record FileSandboxProperties(List<String> allowedPaths) {

  public FileSandboxProperties {
    allowedPaths =
        (allowedPaths == null)
            ? List.of()
            : List.copyOf(allowedPaths.stream().filter(Objects::nonNull).toList());
  }
}
