package com.sableos.core.profile;

import java.util.List;

/**
 * 一个 Agent 的完整运行声明：由 {@code AgentLoader} 从 {@code .sableos/agents/<name>/AGENT.md} 的 frontmatter
 * 派生（技术方案 §8.2；字段集不含 skills / notify_channels）。
 *
 * <p>本节只承载与校验本节消费的字段（name / provider / settings），其余字段解析后随对应能力节消费。
 *
 * @param name Agent 名（必须与目录名一致）
 * @param description Agent 简述
 * @param identity Agent 身份（展示名 + 主提示词）
 * @param provider 本次运行使用的 provider 与模型
 * @param tools 工具名列表（20 节起消费）
 * @param mcpServers MCP server 名列表（20 节起消费）
 * @param channels 渠道名列表（18/26 节起消费）
 * @param schedules 定时任务声明（25 节起消费）
 * @param bootstrap Bootstrap 文件名列表（17 节起消费）
 * @param settings 运行参数（含默认值，恒非 null）
 */
public record Profile(
    String name,
    String description,
    Identity identity,
    ProviderRef provider,
    List<String> tools,
    List<String> mcpServers,
    List<String> channels,
    List<ScheduleConfig> schedules,
    List<String> bootstrap,
    Settings settings) {

  public Profile {
    // 内联 List.copyOf：SpotBugs 经 helper 间接调用无法识别不可变包装（EI_EXPOSE_REP），勿改回抽方法
    tools = (tools == null) ? List.of() : List.copyOf(tools);
    mcpServers = (mcpServers == null) ? List.of() : List.copyOf(mcpServers);
    channels = (channels == null) ? List.of() : List.copyOf(channels);
    schedules = (schedules == null) ? List.of() : List.copyOf(schedules);
    bootstrap = (bootstrap == null) ? List.of() : List.copyOf(bootstrap);
    settings = (settings == null) ? new Settings(null, null) : settings;
  }

  /** Agent 身份：展示用名与主提示词。 */
  public record Identity(String agentName, String prompt) {}

  /** provider 与模型选择：name 引用实例清单，model 决定调用落点（换模型只改这里）。 */
  public record ProviderRef(String name, String model, Double temperature) {}

  /** 定时任务声明（25 节起消费）。 */
  public record ScheduleConfig(String id, String cron, String zone, String message) {}

  /** 运行参数：迭代上限与历史轮数截断（17 节消费），缺省取默认值。 */
  public record Settings(Integer maxIterations, Integer maxHistoryTurns) {

    /** 迭代上限默认值（脚手架写 frontmatter 时也取这一处，避免两处常量漂移）。 */
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    /** 历史轮数默认值。 */
    public static final int DEFAULT_MAX_HISTORY_TURNS = 20;

    public Settings {
      if (maxIterations == null) {
        maxIterations = DEFAULT_MAX_ITERATIONS;
      }
      if (maxHistoryTurns == null) {
        maxHistoryTurns = DEFAULT_MAX_HISTORY_TURNS;
      }
    }
  }
}
