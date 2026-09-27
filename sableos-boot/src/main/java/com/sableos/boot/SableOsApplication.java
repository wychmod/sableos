package com.sableos.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sableos")
public class SableOsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SableOsApplication.class, args);
    }
}
