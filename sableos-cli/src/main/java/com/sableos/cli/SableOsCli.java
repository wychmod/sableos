package com.sableos.cli;

import java.io.PrintWriter;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
        name = "sableos",
        mixinStandardHelpOptions = true,
        versionProvider = SableOsCli.VersionProvider.class,
        description = "SableOS 命令行入口")
public class SableOsCli implements Runnable {

    @Spec
    private CommandSpec spec;

    public static void main(String[] args) {
        System.exit(new CommandLine(new SableOsCli()).execute(args));
    }

    @Override
    public void run() {
        PrintWriter out = spec.commandLine().getOut();
        for (String line : spec.version()) {
            out.println(line);
        }
        out.println("运行 sableos --help 查看可用命令。");
        out.flush();
    }

    static class VersionProvider implements IVersionProvider {

        @Override
        public String[] getVersion() {
            String version = SableOsCli.class.getPackage().getImplementationVersion();
            return new String[] {"SableOS " + (version != null ? version : "dev")};
        }
    }
}
