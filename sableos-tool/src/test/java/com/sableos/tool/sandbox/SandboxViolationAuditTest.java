package com.sableos.tool.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sableos.core.provider.ToolCallRequest;
import com.sableos.core.tool.SableTool;
import com.sableos.core.tool.ToolExecutor;
import com.sableos.core.tool.ToolInvocationAuditor;
import com.sableos.core.tool.ToolResult;
import com.sableos.tool.ToolRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SC-005 直证：{@link SandboxViolationException} 经 {@link ToolExecutor} 收敛为失败结果并写入失败审计。 */
class SandboxViolationAuditTest {

  private static final String SESSION_ID = "s-1";

  @Test
  @DisplayName("沙箱拒绝_收敛为失败结果且审计含被拒目标")
  void sandboxViolation_isAuditedAsFailure(@TempDir Path dir) {
    WhitelistSandbox sandbox =
        new WhitelistSandbox(
            new FileSandboxProperties(List.of(dir.toString())),
            new ShellSandboxProperties(List.of()),
            new HttpSandboxProperties(List.of()));
    String deniedTarget = dir.resolveSibling("outside.txt").toString();

    SableTool guardedTool =
        new SableTool() {
          @Override
          public String getName() {
            return "write_file";
          }

          @Override
          public String getDescription() {
            return "测试工具";
          }

          @Override
          public String getInputSchema() {
            return "{\"type\":\"object\"}";
          }

          @Override
          public ToolResult execute(JsonNode input) {
            sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, input.get("path").asText()));
            throw new AssertionError("沙箱未拦截，文件写入动作已执行");
          }
        };
    ToolRegistry registry = new ToolRegistry();
    registry.register(guardedTool);

    List<Audit> audits = new ArrayList<>();
    ToolInvocationAuditor auditor =
        (sessionId, toolName, inputJson, resultJson, success, errorMessage, durationMs) ->
            audits.add(
                new Audit(sessionId, toolName, inputJson, resultJson, success, errorMessage));
    ToolExecutor executor = new ToolExecutor(registry, auditor);
    String arguments = new ObjectMapper().createObjectNode().put("path", deniedTarget).toString();

    ToolResult result =
        executor.execute(SESSION_ID, new ToolCallRequest("c-1", "write_file", arguments));

    assertFalse(result.success());
    assertTrue(result.retryable());
    assertTrue(result.errorMessage().contains("工具执行异常："), result.errorMessage());
    assertTrue(
        result.errorMessage().contains(deniedTarget),
        "失败原因必须点名被拒目标（FR-007）: " + result.errorMessage());

    assertEquals(1, audits.size());
    Audit audit = audits.get(0);
    assertEquals(SESSION_ID, audit.sessionId());
    assertEquals("write_file", audit.toolName());
    assertFalse(audit.success());
    assertNull(audit.resultJson());
    assertTrue(
        audit.errorMessage().contains(deniedTarget),
        "审计失败原因必须含被拒目标（SC-005）: " + audit.errorMessage());
  }

  private record Audit(
      String sessionId,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage) {}
}
