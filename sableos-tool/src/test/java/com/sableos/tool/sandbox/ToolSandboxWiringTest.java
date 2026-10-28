package com.sableos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 结构纪律：{@code builtin} 包下所有 {@code @Tool} 公开方法的<strong>首条语句必须是 {@code
 * sandbox.enforce(...)}</strong>。
 *
 * <p>为什么需要这条断言：24 节的校验调用点写在每个工具方法的<strong>方法体内</strong>，而不是 {@code ToolExecutor}
 * 里（执行器只负责把失败收敛为审计）。于是"记得调"完全依赖人的自觉 ——
 * 新增一个涉外工具忘了写首行校验，代码照常编译、其余测试照常通过，白名单被绕过而无人察觉。本测试把这条纪律从注释里的约定变成 CI 红线（代码走读 §5-A 待办第 1 条）。
 *
 * <p>判定方式：取方法内全部访问（字段访问 / 方法调用 / 构造调用），以 {@code Sandbox.enforce}
 * 调用的最小行号为基准，要求<strong>没有任何访问的行号早于它</strong>。行号来自字节码 LineNumberTable，语义上等价于"enforce
 * 之前没有其它能产生副作用的表达式"。纯局部变量声明（不产生访问）不触发告警 —— 它们本身不涉外，不属于本规则要防的风险。
 *
 * <p><strong>已知盲区（勿误以为全覆盖）</strong>：
 *
 * <ul>
 *   <li>{@code NotifyTools} 直接实现 {@code SableTool} 而非 {@code @Tool}，不在本规则网内；它的"先校验后发送"由 {@code
 *       NotifyToolsTest} 的 {@code InOrder} 钉住。
 *   <li>{@code DuckDuckGoSearchProvider} 不是工具方法，校验在其内部首个动作前完成。
 *   <li>MCP 工具由 {@code ToolRegistry.register()} 注册，其行为不受本规则约束 ——
 *       这正是"沙箱只罩工具调用路径"这一架构事实的体现，不是本测试能解决的。
 * </ul>
 */
class ToolSandboxWiringTest {

  /** 被规则扫描的包：{@code @Tool} 注解工具的归属地。 */
  private static final String BUILTIN_PACKAGE = "com.sableos.tool.builtin";

  /**
   * 已知豁免：方法全名 → 豁免理由。豁免必须写理由，且由 {@link #exemptions_must_reference_existing_tool_methods()}
   * 防止表项腐化（方法被删或改名后过期豁免会让 CI 红）。
   */
  private static final Map<String, String> EXEMPTIONS =
      Map.of(
          "com.sableos.tool.builtin.InteractionTools.askUser(java.lang.String)",
          "只走本地 UserInteraction（控制台 / 不支持两态），不产生文件、进程或网络动作，没有涉外目标可供校验",
          "com.sableos.tool.builtin.WebSearchTools.webSearch(java.lang.String)",
          "校验下沉在 SearchProvider 实现（DuckDuckGoSearchProvider 首个动作即 enforce）；工具侧入参只有查询词，拿不到 URL");

  /**
   * 扫描到的 {@code @Tool} 方法数下限。低于它说明包名或注解换过、规则正在空转（"零目标通过"），比断言失败更危险。 新增工具不必改此数；若某次重构让计数下降，须先确认不是漏注册。
   */
  private static final int MIN_EXPECTED_TOOL_METHODS = 11;

  private static JavaClasses builtinClasses;

  @BeforeAll
  static void importBuiltinClasses() {
    // 必须排除 test-classes：本包下有 FileToolsTest 等同包测试类，否则规则会把测试代码也当成被约束对象
    builtinClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BUILTIN_PACKAGE);
  }

  @Test
  @DisplayName("builtin_所有@Tool方法首条语句必须是sandbox.enforce")
  void all_tool_methods_must_enforce_sandbox_before_any_other_access() {
    List<JavaMethod> toolMethods = toolMethods();
    assertThat(toolMethods)
        .as(
            "在 %s 下只导入到 %d 个 @Tool 方法（下限 %d）——包名或注解可能已变更，规则存在空转风险",
            BUILTIN_PACKAGE, toolMethods.size(), MIN_EXPECTED_TOOL_METHODS)
        .hasSizeGreaterThanOrEqualTo(MIN_EXPECTED_TOOL_METHODS);

    List<String> violations =
        toolMethods.stream()
            .filter(method -> !EXEMPTIONS.containsKey(method.getFullName()))
            .flatMap(
                method ->
                    violationOf(method).stream().map(reason -> method.getFullName() + " " + reason))
            .toList();

    assertThat(violations)
        .as(
            "以下 @Tool 方法没有把 sandbox.enforce(...) 放在首条语句：涉外的文件 / Shell / HTTP 动作"
                + "可能在白名单校验之前就已发生。修法：把 sandbox.enforce(new SandboxAction(...)) 提到方法第一行；"
                + "若该工具确实没有涉外目标，请把它加入 EXEMPTIONS 并写明理由。")
        .isEmpty();
  }

  @Test
  @DisplayName("builtin_豁免表项必须对应真实存在的@Tool方法")
  void exemptions_must_reference_existing_tool_methods() {
    Set<String> actual =
        toolMethods().stream().map(JavaMethod::getFullName).collect(Collectors.toSet());

    assertThat(actual)
        .as("EXEMPTIONS 中存在失效条目（方法已删除、改名或不再是 @Tool）——请同步清理，避免豁免表腐化")
        .containsAll(EXEMPTIONS.keySet());
  }

  // ---------- 内部实现 ----------

  private static List<JavaMethod> toolMethods() {
    return builtinClasses.stream()
        .flatMap(javaClass -> javaClass.getMethods().stream())
        .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
        .filter(method -> method.isAnnotatedWith(Tool.class))
        .sorted(Comparator.comparing(JavaMethod::getFullName))
        .toList();
  }

  /** 返回违规原因；空列表代表合规（enforce 存在，且之前没有任何访问）。 */
  private static List<String> violationOf(JavaMethod method) {
    Set<JavaAccess<?>> accesses = method.getAccessesFromSelf();

    OptionalInt enforceLine =
        accesses.stream()
            .filter(access -> access instanceof JavaMethodCall)
            .map(JavaMethodCall.class::cast)
            .filter(ToolSandboxWiringTest::isSandboxEnforce)
            .mapToInt(JavaAccess::getLineNumber)
            .filter(line -> line > 0)
            .min();

    if (enforceLine.isEmpty()) {
      return List.of("未找到 sandbox.enforce(...) 调用");
    }

    int first = enforceLine.getAsInt();
    Map<Integer, String> earlier = new LinkedHashMap<>();
    accesses.stream()
        .filter(access -> access.getLineNumber() > 0 && access.getLineNumber() < first)
        .sorted(Comparator.comparingInt(JavaAccess::getLineNumber))
        .forEach(access -> earlier.putIfAbsent(access.getLineNumber(), String.valueOf(access)));

    if (earlier.isEmpty()) {
      return List.of();
    }
    return List.of("在 enforce（第 " + first + " 行）之前还有访问：" + earlier);
  }

  /** 目标方法名为 enforce 且声明方是 Sandbox 或其实现——不绑死参数签名，接口演进时不误报。 */
  private static boolean isSandboxEnforce(JavaMethodCall call) {
    return "enforce".equals(call.getTarget().getName())
        && call.getTargetOwner().isAssignableTo(Sandbox.class);
  }
}
