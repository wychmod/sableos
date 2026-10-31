package com.sableos.core.profile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 派生 Profile 的进程内索引：按 name 提供快速查找（技术方案 §8.2）。
 *
 * <p>启动期由 {@code AgentLoader} 装载；核心阶段多个 Agent 并存、共用一份注册表。
 */
public class ProfileRegistry {

  private final Map<String, Profile> profiles = new ConcurrentHashMap<>();

  /** 注册（或覆盖同名）一个 Profile。 */
  public void register(Profile profile) {
    profiles.put(profile.name(), profile);
  }

  /** 按名查找；未注册返回 null。 */
  public Profile get(String name) {
    return profiles.get(name);
  }

  /** 是否存在同名 Profile。 */
  public boolean exists(String name) {
    return profiles.containsKey(name);
  }

  /**
   * 运行时注销；存在并移除返回 {@code true}，未命中返回 {@code false}。
   *
   * <p>与 {@link #register} 一样不做配置校验：非法声明在 {@code AgentLoader.deriveProfile} 处已被拒绝， 本类只负责存取（第 29
   * 节：启动扫描与运行时新增共用同一份注册表、同一套校验）。
   *
   * @param name Agent 名
   * @return 确实移除了返回 true
   */
  public boolean remove(String name) {
    return profiles.remove(name) != null;
  }

  /** 全部 Profile 快照（顺序不保证）。 */
  public List<Profile> all() {
    return List.copyOf(profiles.values());
  }
}
