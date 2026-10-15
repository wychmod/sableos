package com.sableos.cli.command;

import java.nio.file.Path;

/**
 * 工作区路径解析（轻命令共用，零 Spring）。
 *
 * <p>根目录默认 {@code .sableos}，可用环境变量 {@code WORKSPACE_ROOT} 覆盖——与 {@code application.yaml} 的 {@code
 * ${workspace.root:.sableos}} 同口径，保证轻命令与重命令看到同一个工作区。
 */
final class Workspace {

  /** 工作区根的环境变量名（与 application.yaml 注释一致）。 */
  static final String ROOT_ENV = "WORKSPACE_ROOT";

  private static final String DEFAULT_ROOT = ".sableos";

  private Workspace() {}

  static Path root() {
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
