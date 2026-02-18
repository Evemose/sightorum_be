package com.rorm.client.config;

import org.springframework.beans.factory.config.CustomScopeConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.SimpleTransactionScope;

@Configuration
public class ScopesConfig {

    public static final String TRANSACTION_SCOPE = "transaction";

    @Bean
    public static CustomScopeConfigurer customScopeConfigurer() {
        var configurer = new CustomScopeConfigurer();
        configurer.addScope(TRANSACTION_SCOPE, new SimpleTransactionScope());
        return configurer;
    }

}
