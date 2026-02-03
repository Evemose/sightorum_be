package com.rorm.client.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "com.rorm.client")
@EnableConfigurationProperties(RormClientProperties.class)
public class RormClientAutoConfiguration {
}
