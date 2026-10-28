package com.sableos.tool.sandbox;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP 域名白名单（技术方案 §6.7）：允许访问的域名，支持 {@code *.example.com} 形式的子域通配。
 *
 * <p>空列表 = 该类动作全部拒绝（deny-all）。通配必须带点号边界，形似域不得命中。 null 与空白条目会被跳过；防御性拷贝为不可变列表，避免 SpotBugs {@code
 * EI_EXPOSE_REP}（对齐 {@code ProvidersProperties} 先例）。
 *
 * @param allowedDomains 允许访问的域名或通配子域模式
 */
@ConfigurationProperties(prefix = "http")
public record HttpSandboxProperties(List<String> allowedDomains) {

  public HttpSandboxProperties {
    allowedDomains =
        (allowedDomains == null)
            ? List.of()
            : List.copyOf(allowedDomains.stream().filter(Objects::nonNull).toList());
  }
}
