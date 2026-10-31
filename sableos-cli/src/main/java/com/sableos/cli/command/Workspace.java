package com.sableos.cli.command;

import java.nio.file.Path;

/**
 * 工作区路径解析（轻命令共用，零 Spring）。
 *
 * <p>根目录优先级：系统属性 {@code sableos.root}（27 节）→ 环境变量 {@code WORKSPACE_ROOT} → 默认 {@code .sableos}。与
 * {@code application.yaml} 的 {@code workspace.root: ${sableos.root:.sableos}} 同口径（Spring
 * 侧环境变量优先级仍高于配置文件），保证轻命令与重命令看到同一个工作区。
 */
final class Workspace {

  /** 工作区根的环境变量名（与 application.yaml 注释一致）。 */
  static final String ROOT_ENV = "WORKSPACE_ROOT";

  /** 工作区根的系统属性名（27 节引入，与 {@code application.yaml} 的占位符同名）。 */
  static final String ROOT_PROPERTY = "sableos.root";

  private static final String DEFAULT_ROOT = ".sableos";

  private Workspace() {}

  static Path root() {
    String system = System.getProperty(ROOT_PROPERTY);
    if (system != null && !system.isBlank()) {
      return Path.of(system);
    }
    String env = System.getenv(ROOT_ENV);
    return Path.of((env == null || env.isBlank()) ? DEFAULT_ROOT : env);
  }

  static Path agentsDir() {
    return root().resolve("agents");
  }

  static Path dbFile() {
    return root().resolve("sableos.db");
  }
}
