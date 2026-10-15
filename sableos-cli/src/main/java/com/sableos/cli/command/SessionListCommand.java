package com.sableos.cli.command;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import picocli.CommandLine.Command;

/**
 * 轻命令：会话概览。裸 JDBC 只读查 {@code sessions} 表（与重命令读同一个库），零 Spring（对齐 oryxos {@code SessionListCommand}）。
 *
 * <p>轻/重判据是"要不要调模型/跑引擎"——列个表不要，故不起 4 秒上下文；SQLite 驱动运行期由 fat JAR 提供。
 */
@Command(
    name = "session",
    description = "Session 相关操作",
    mixinStandardHelpOptions = true,
    subcommands = SessionListCommand.ListCommand.class)
public class SessionListCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  @Command(name = "list", description = "列出会话", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {

    private static final String QUERY =
        "SELECT session_id, profile_name, status, last_active_at FROM sessions"
            + " ORDER BY last_active_at DESC";

    @Override
    public void run() {
      if (LightDbConfig.sqliteFileMissing()) {
        System.out.println("暂无会话（" + LightDbConfig.describe() + " 尚未创建，首次 chat 时生成）。");
        return;
      }
      try (Connection conn = LightDbConfig.connect();
          Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery(QUERY)) {
        boolean any = false;
        while (rs.next()) {
          any = true;
          String lastActive = rs.getString("last_active_at");
          System.out.printf(
              "%-40s %-16s %-9s %s%n",
              rs.getString("session_id"),
              rs.getString("profile_name"),
              rs.getString("status"),
              lastActive == null ? "-" : lastActive);
        }
        if (!any) {
          System.out.println("暂无会话。");
        }
      } catch (SQLException e) {
        System.err.println("查询会话失败: " + e.getMessage());
      }
    }
  }
}
