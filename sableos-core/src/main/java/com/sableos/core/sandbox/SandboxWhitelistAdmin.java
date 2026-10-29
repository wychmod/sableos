package com.sableos.core.sandbox;

import java.util.List;

/**
 * 白名单的运行时管理契约。{@code sableos-web} 的 Controller 只依赖本接口，不认 tool 的实现类。
 *
 * <p><b>契约不变量</b>：
 *
 * <ul>
 *   <li>任何写方法**成功返回时**，运行时校验策略 MUST 已反映该变更（调用返回即可见，无需重启）。
 *   <li>任何写方法**失败抛出时**，运行时策略 MUST 保持不变——不允许"库没写成功但内存已改"。
 *   <li>{@link #list} / {@link #get} 读库的真实状态，不读运行时快照：管理面要看到"库里有什么"，而不是"内存此刻在用什么"。
 * </ul>
 *
 * <p><b>安全边界</b>：这组操作等价于远程调整安全护栏（可加"任意目录"与"解释器"）。核心阶段 Web 层无认证（技术方案 §7.5），暴露面必须靠网络层兜底。
 */
public interface SandboxWhitelistAdmin {

  /**
   * 列出条目。
   *
   * @param type {@code null} 表示全部
   */
  List<WhitelistEntry> list(WhitelistType type);

  /**
   * 查单条。
   *
   * @throws WhitelistEntryNotFoundException id 不存在
   */
  WhitelistEntry get(long id);

  /**
   * 新增：归一 → 校验 → 判重 → 落库 → 刷新快照。
   *
   * @throws WhitelistValidationException 值不合规
   * @throws WhitelistDuplicateException {@code (type, 归一后的值)} 已存在
   */
  WhitelistEntry add(WhitelistCommand command);

  /**
   * 更新值与说明；{@code type} 不可变（要换类型请删除后新增）。流程同 {@link #add}，判重时排除自身。
   *
   * @throws WhitelistEntryNotFoundException id 不存在
   * @throws WhitelistValidationException 值不合规
   * @throws WhitelistDuplicateException 新值与该类型的另一条重复；**原值保持不变**
   */
  WhitelistEntry update(long id, String value, String description);

  /**
   * 删除 → 刷新快照。
   *
   * @throws WhitelistEntryNotFoundException id 不存在
   */
  void delete(long id);
}
