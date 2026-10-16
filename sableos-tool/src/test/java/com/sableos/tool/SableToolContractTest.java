package com.sableos.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.sableos.core.tool.SableTool;
import com.sableos.tool.builtin.FileTools;
import com.sableos.tool.builtin.HttpTools;
import com.sableos.tool.builtin.InteractionTools;
import com.sableos.tool.builtin.NotifyTools;
import com.sableos.tool.builtin.ShellTools;
import com.sableos.tool.builtin.WebSearchTools;
import com.sableos.tool.interaction.UnsupportedUserInteraction;
import com.sableos.tool.sandbox.PermissiveSandbox;
import com.sableos.tool.sandbox.Sandbox;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestClient;

/**
 * 契约三件套参数化遍历（课件"验收 harness"回归点）：装配同构地注册全部工具后，逐个断言 name/description/inputSchema 全非空——新工具一经注册自动纳入本遍历，
 * 漏填契约即刻挂。
 */
class SableToolContractTest {

  /** 按运行时装配同构构造注册表：内置五（注解管道）+ NotifyTools（空渠道表，直接实现），返回全部已注册工具。 */
  static Stream<Arguments> allRegisteredTools() {
    Sandbox sandbox = new PermissiveSandbox();
    RestClient restClient = RestClient.create();
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new FileTools(sandbox));
    registry.registerAnnotated(new ShellTools(sandbox));
    registry.registerAnnotated(new HttpTools(sandbox, restClient));
    registry.registerAnnotated(new InteractionTools(new UnsupportedUserInteraction()));
    registry.registerAnnotated(new WebSearchTools(query -> List.of()));
    registry.register(new NotifyTools(sandbox, (target, content) -> {}, Map.of()));
    return registry.all().stream().map(tool -> Arguments.of(tool.getName(), tool));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allRegisteredTools")
  @org.junit.jupiter.api.DisplayName("每个工具的契约三件套都不能缺")
  void everyTool_hasContractTriple(String name, SableTool tool) {
    assertThat(tool.getName()).isNotBlank();
    assertThat(tool.getDescription()).isNotBlank();
    assertThat(tool.getInputSchema()).isNotBlank();
  }
}
