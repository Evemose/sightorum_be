package com.rorm.dataimport;

import com.rorm.dataimport.pipeline.profile.SchemaProfile;
import com.rorm.dataimport.pipeline.profile.SchemaProfileStore;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication(scanBasePackages = {"com.rorm.dataimport", "com.rorm.engine", "com.rorm.fetcher"})
public class TestApplication {

    static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }

    @Bean
    public DSLContext dslContext(DataSource dataSource) {
        return DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Bean
    public SchemaProfileStore schemaProfileStore() {
        return new SchemaProfileStore() {
            private final ConcurrentHashMap<String, SchemaProfile> store = new ConcurrentHashMap<>();

            @Override
            public void store(String schema, SchemaProfile profile) {
                store.put(schema, profile);
            }

            @Override
            public Optional<SchemaProfile> get(String schema) {
                return Optional.ofNullable(store.get(schema));
            }

            @Override
            public void remove(String schema) {
                store.remove(schema);
            }
        };
    }
}
