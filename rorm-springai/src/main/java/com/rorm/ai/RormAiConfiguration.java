package com.rorm.ai;

import com.rorm.ai.swarm.SwarmConfig;
import com.rorm.misc.YamlPropertySource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    RormAiProperties.class,
    SwarmConfig.class
})
@YamlPropertySource("classpath:application-ai.yaml")
public class RormAiConfiguration {
}
