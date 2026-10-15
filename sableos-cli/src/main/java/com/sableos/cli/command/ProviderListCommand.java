package com.sableos.cli.command;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import picocli.CommandLine.Command;

/**
 * 轻命令：列出已配置的 Provider（读 classpath 的 {@code application.yaml} 的 {@code sableos.providers}）。不起
 * Spring。
 *
 * <p>只读展示配置的 provider 名与类型，**不做类型扫描**（宪法 IV：Provider 显式映射）。运行期的真实注册与凭证解析归 ProviderService（16 节）。
 */
@Command(
    name = "provider",
    description = "Provider 相关操作",
    mixinStandardHelpOptions = true,
    subcommands = ProviderListCommand.ListCommand.class)
public class ProviderListCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  @Command(name = "list", description = "列出已配置的 Provider", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {

    @Override
    public void run() {
      List<Map<String, Object>> providers = readProviders();
      if (providers.isEmpty()) {
        System.out.println("暂无 provider 配置（application.yaml 的 sableos.providers 为空）。");
        System.out.println("凭证走环境变量占位；在此登记 name/type 后即可被 Profile 按名引用。");
        return;
      }
      System.out.printf("%-20s %-12s%n", "NAME", "TYPE");
      for (Map<String, Object> p : providers) {
        System.out.printf(
            "%-20s %-12s%n",
            str(p.get("name")), p.get("type") == null ? "openai" : str(p.get("type")));
      }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readProviders() {
      try (InputStream in = ProviderListCommand.class.getResourceAsStream("/application.yaml")) {
        if (in == null) {
          return List.of();
        }
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object root = yaml.load(in);
        if (!(root instanceof Map)) {
          return List.of();
        }
        Object sableos = ((Map<String, Object>) root).get("sableos");
        if (!(sableos instanceof Map)) {
          return List.of();
        }
        Object providers = ((Map<String, Object>) sableos).get("providers");
        if (!(providers instanceof List)) {
          return List.of();
        }
        return (List<Map<String, Object>>) providers;
      } catch (IOException | RuntimeException e) {
        System.err.println("读取 provider 配置失败: " + e.getMessage());
        return List.of();
      }
    }

    private static String str(Object o) {
      return o == null ? "" : String.valueOf(o);
    }
  }
}
