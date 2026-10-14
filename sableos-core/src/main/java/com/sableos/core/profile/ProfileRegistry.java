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

  /** 全部 Profile 快照（顺序不保证）。 */
  public List<Profile> all() {
    return List.copyOf(profiles.values());
  }
}
