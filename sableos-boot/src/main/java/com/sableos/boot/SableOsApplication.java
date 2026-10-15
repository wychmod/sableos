package com.sableos.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 最小 Spring Boot 应用类（vestigial，对齐 oryxos {@code OryxOsApplication}）。
 *
 * <p>18 节起，真正的装配根与 JPA 扫描声明（{@code @EnableJpaRepositories}/{@code @EntityScan}）移到 cli 的 {@code
 * SableOsRuntime}—— 可执行 fat JAR 的 {@code mainClass} 也改指 {@code com.sableos.cli.SableOsCli}（重命令自启动
 * {@code SableOsRuntime}）。 本类只保留最小 {@code @SpringBootApplication}，不再声明 JPA 扫描，避免与 {@code
 * SableOsRuntime} 重复注册仓库。
 */
@SpringBootApplication(scanBasePackages = "com.sableos")
public class SableOsApplication {

  public static void main(String[] args) {
    SpringApplication.run(SableOsApplication.class, args);
  }
}
