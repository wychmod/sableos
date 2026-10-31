package com.sableos.core.provider;

import java.util.List;

/**
 * 模型提供方实时连通探测的前向契约（26 节 {@code GET /api/v1/info}）。
 *
 * <p>只做 HTTP 连通性探测，不调用模型、不消耗 token；单个提供方失败只影响该条，不得让整体探测抛异常。返回顺序与 {@code sableos.providers} 声明顺序一致。
 */
public interface ProviderHealthProbe {

  /** 探测全部已配置提供方；清单为空时返回空表。 */
  List<ProviderStatus> probe();
}
