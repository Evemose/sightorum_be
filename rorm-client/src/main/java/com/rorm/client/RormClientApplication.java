package com.rorm.client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EntityScan
@EnableRetry
@EnableAsync
@EnableScheduling
@EnableJpaRepositories
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class RormClientApplication {

    static void main(String[] args) {
        SpringApplication.run(RormClientApplication.class, args);
    }
}
