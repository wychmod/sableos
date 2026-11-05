package com.sableos.tool.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
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
 * @param allowedRoots 允许读写的路径根（已绝对化、归一，并解析为真实路径）
 * @param allowedCommands 允许执行的可执行文件首 token
 * @param allowedDomainPatterns 允许访问的域名或 {@code *.host} 通配模式（小写）
 */
public record WhitelistPolicy(
    List<Path> allowedRoots, Set<String> allowedCommands, List<String> allowedDomainPatterns) {

  public WhitelistPolicy {
    allowedRoots = List.copyOf(canonicalRoots(allowedRoots));
    allowedCommands = (allowedCommands == null) ? Set.of() : Set.copyOf(allowedCommands);
    allowedDomainPatterns =
        (allowedDomainPatterns == null) ? List.of() : List.copyOf(allowedDomainPatterns);
  }

  /** 空策略 = 三类全部拒绝（deny-all），不是"不校验"。 */
  public static WhitelistPolicy empty() {
    return new WhitelistPolicy(List.of(), Set.of(), List.of());
  }

  /**
   * 根统一取真实路径：白名单条目入库时是词法规约（{@code normalize()} + 分隔符统一），而校验侧会把待查路径也解析成真实路径再 {@code startsWith}
   * 比对。若根不解析，两侧形态不一致就会误判越界——Windows 的 8.3 短名（CI 的 {@code RUNNER~1}）与根上的 符号链接都会触发；仓库 2026-11-05 的
   * Windows CI 即因此失败。
   */
  private static List<Path> canonicalRoots(List<Path> roots) {
    if (roots == null) {
      return List.of();
    }
    return roots.stream().map(WhitelistPolicy::resolveRealPath).toList();
  }

  /**
   * 取 {@code target} 的真实路径（解析途中所有符号链接）。
   *
   * <p>目标不存在时（典型场景是 {@code write_file} 写新文件）无法直接 {@code toRealPath}，退到最近的已存在祖先取真实路径，
   * 再把跳过的相对段拼回去。若不这么退让而直接返回 normalize 结果，那么「先在白名单内建一个指向外部的软链目录、再往里写新文件」 就能绕过校验——因为路径的最后一段此时尚不存在。
   *
   * <p>整条路径都不存在（连根都没有）时返回原 {@code target}，由调用方按白名单拒绝。
   */
  static Path resolveRealPath(Path target) {
    Path existing = target;
    Deque<Path> missing = new ArrayDeque<>();
    while (existing != null) {
      try {
        Path real = existing.toRealPath();
        Path result = real;
        for (Path segment : missing) {
          result = result.resolve(segment);
        }
        return result;
      } catch (IOException e) {
        Path parent = existing.getParent();
        Path name = existing.getFileName();
        // 根路径没有父也没有文件名（getParent/getFileName 均为 null），到此无法再向上退，
        // 直接返回原路径交给调用方按白名单拒绝。
        if (parent == null || name == null) {
          break;
        }
        // addFirst 保证出栈顺序与路径自顶向下的顺序一致
        missing.addFirst(name);
        existing = parent;
      }
    }
    return target;
  }
}
