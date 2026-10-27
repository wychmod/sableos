package com.sableos.memory;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** sableos-memory 模块测试引导类：为 JPA 仓库测试提供最小 Spring Boot 上下文。 */
@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.sableos.storage.memory")
@EntityScan(basePackages = "com.sableos.storage.memory")
public class MemoryTestApplication {}
