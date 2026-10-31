package com.sableos.provider;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全局 provider 清单（{@code sableos.providers}；契约见 contracts/provider-config.md）。
 *
 * <p>空清单合法——无 key 环境与 CI 可正常启动；任何违规由 {@link #validate()} 在启动装配期 直接失败并点名该 provider，不存在「静默跳过某个
 * provider」。
 */
@ConfigurationProperties(prefix = "sableos")
public record ProvidersProperties(List<Provider> providers) {

  static final String TYPE_OPENAI = "openai";
  static final String TYPE_DASHSCOPE = "dashscope";

  /** 第 27 节的确定性 mock provider：不连模型、不需要 key，专供无 key 全链路自测与演示。 */
  static final String TYPE_MOCK = "mock";

  public ProvidersProperties {
    providers = (providers == null) ? List.of() : List.copyOf(providers);
  }

  /** 启动期校验：违规即抛点名异常（启动失败快）。 */
  public void validate() {
    Set<String> seen = new HashSet<>();
    for (int index = 0; index < providers.size(); index++) {
      Provider provider = providers.get(index);
      String name = provider.name();
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("providers[" + index + "] 的 name 不能为空");
      }
      if (!seen.add(name)) {
        throw new IllegalArgumentException("provider 名重复：" + name);
      }
      String where = "provider '" + name + "'";
      String type = provider.effectiveType();
      if (!TYPE_OPENAI.equals(type) && !TYPE_DASHSCOPE.equals(type) && !TYPE_MOCK.equals(type)) {
        throw new IllegalArgumentException(
            where + " 的 type 非法：" + provider.type() + "（仅支持 openai | dashscope | mock）");
      }
      if (TYPE_MOCK.equals(type)) {
        // mock 是第 27 节的无 key 自测 provider：既不需要凭证，也没有网络端点可校验。
        continue;
      }
      String apiKey = provider.apiKey();
      if (apiKey == null || apiKey.isBlank()) {
        throw new IllegalArgumentException(where + " 的 api-key 不能为空（应经环境变量占位注入）");
      }
      if (apiKey.contains("${")) {
        throw new IllegalArgumentException(where + " 的 api-key 含未展开的 ${（视为漏配环境变量）");
      }
      boolean baseUrlMissing = provider.baseUrl() == null || provider.baseUrl().isBlank();
      if (TYPE_OPENAI.equals(type) && baseUrlMissing) {
        throw new IllegalArgumentException(where + " 为 openai 型，必须提供 base-url");
      }
    }
  }

  /** 单条 provider 声明。 */
  public record Provider(String name, String type, String apiKey, String baseUrl) {

    /** 有效协议类型：缺省 openai。 */
    public String effectiveType() {
      return (type == null || type.isBlank()) ? TYPE_OPENAI : type.trim();
    }
  }
}
