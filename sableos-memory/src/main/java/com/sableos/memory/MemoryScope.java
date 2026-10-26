package com.sableos.memory;

/**
 * 长期记忆的作用域：核心记忆始终完整拼进上下文，归档记忆可被截断和检索。
 *
 * <p>Agent 通过 {@code save_memory} 的 {@code scope} 参数显式指定；系统不猜测。
 */
public enum MemoryScope {
  CORE,
  ARCHIVAL
}
