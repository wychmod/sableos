package com.sableos.tool.mcp;

import java.util.Map;

/**
 * 一个 MCP server 的配置条目（`.sableos/mcp_servers.yaml` 的一条）。
 *
 * @param name server 名（日志与点名用）
 * @param transport 传输方式（核心阶段仅 stdio）
 * @param command 启动命令（空白拆分归 {@link McpClientService}）
 * @param env 环境变量（缺省为空 Map）
 */
public record McpServerConfig(
    String name, String transport, String command, Map<String, String> env) {

  public McpServerConfig {
    // 防御性拷贝为不可变 Map：env() 返回内部表示不泄露（对齐 NotifyTarget 先例，清 EI_EXPOSE_REP/REP2）
    env = (env == null) ? Map.of() : Map.copyOf(env);
  }
}
