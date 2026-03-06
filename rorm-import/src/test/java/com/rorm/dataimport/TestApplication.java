package com.rorm.dataimport;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

@SpringBootApplication(scanBasePackages = {"com.rorm.dataimport", "com.rorm.engine", "com.rorm.fetcher"})
public class TestApplication {

    static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }

    @Bean
    public DSLContext dslContext(DataSource dataSource) {
        return DSL.using(dataSource, SQLDialect.POSTGRES);
    }

}
