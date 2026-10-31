package com.sableos.web.api.dto;

import java.util.List;

/** 系统信息视图（26 节 {@code GET /info}）：应用标识、版本与提供方连通状态。 */
public record SystemInfoView(String app, String version, List<ProviderStatusView> providers) {

  public SystemInfoView {
    providers = (providers == null) ? List.of() : List.copyOf(providers);
  }
}
