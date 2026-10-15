package com.sableos.cli.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 轻命令的 datasource 解析（对齐 oryxos {@code LightDbConfig}）：不起 Spring，但与重命令读同一个库。
 *
 * <p>sableos 的库指向由 {@code application.yaml} 的 {@code
 * jdbc:sqlite:${workspace.root:.sableos}/sableos.db} 决定； 轻命令按同一默认（{@link
 * Workspace#dbFile()}）直连，SQLite 驱动运行期由 fat JAR 提供（cli 无编译期依赖）。
 */
final class LightDbConfig {

  private LightDbConfig() {}

  static boolean sqliteFileMissing() {
    return !Files.exists(Workspace.dbFile());
  }

  static String describe() {
    return Workspace.dbFile().toString();
  }

  static Connection connect() throws SQLException {
    Path db = Workspace.dbFile();
    return DriverManager.getConnection("jdbc:sqlite:" + db + "?busy_timeout=5000");
  }
}
