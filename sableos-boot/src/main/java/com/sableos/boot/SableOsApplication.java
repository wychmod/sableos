package com.sableos.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.sableos")
@EntityScan("com.sableos.storage")
@EnableJpaRepositories("com.sableos.storage")
public class SableOsApplication {

  public static void main(String[] args) {
    SpringApplication.run(SableOsApplication.class, args);
  }
}
