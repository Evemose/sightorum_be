package com.rorm.ai;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.misc.YamlPropertySource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@EnableConfigurationProperties({
    RormAiProperties.class,
    DurableSwarmConfig.class
})
@EnableAspectJAutoProxy(proxyTargetClass = true)
@YamlPropertySource("classpath:application-ai.yaml")
public class RormAiConfiguration {
}
