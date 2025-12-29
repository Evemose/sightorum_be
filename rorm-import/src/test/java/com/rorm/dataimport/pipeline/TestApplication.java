package com.rorm.dataimport.pipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.rorm.dataimport")
public class TestApplication {

    static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }

}
