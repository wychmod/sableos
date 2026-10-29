package com.sableos.tool.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 某一时刻生效的白名单策略（不可变快照）。
 *
 * <p>整体替换而非原地修改：{@code enforce} 每次只 {@code AtomicReference#get()}
 * 一次，就能保证单次校验内看到的是**同一版本**的三类策略——不会出现"文件规则用新版、域名规则用旧版"的撕裂视图。
 *
 * <p>所有集合在构造器内做不可变拷贝（{@code List.copyOf}/{@code Set.copyOf}）：快照一经发布就不再变化，调用方也无法通过传进来的可变集合反向影响它。
 *
 * @param allowedRoots 允许读写的路径根（已绝对化并归一）
 * @param allowedCommands 允许执行的可执行文件首 token
 * @param allowedDomainPatterns 允许访问的域名或 {@code *.host} 通配模式（小写）
 */
public record WhitelistPolicy(
    List<Path> allowedRoots, Set<String> allowedCommands, List<String> allowedDomainPatterns) {

  public WhitelistPolicy {
    allowedRoots = (allowedRoots == null) ? List.of() : List.copyOf(allowedRoots);
    allowedCommands = (allowedCommands == null) ? Set.of() : Set.copyOf(allowedCommands);
    allowedDomainPatterns =
        (allowedDomainPatterns == null) ? List.of() : List.copyOf(allowedDomainPatterns);
  }

  /** 空策略 = 三类全部拒绝（deny-all），不是"不校验"。 */
  public static WhitelistPolicy empty() {
    return new WhitelistPolicy(List.of(), Set.of(), List.of());
  }
}
