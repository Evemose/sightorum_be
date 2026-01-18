package com.rorm.jpasupport;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnBean(EntityManagerFactory.class)
public class RormJpaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(SessionFactoryImplementor.class)
    public JpaPhysicalMappingResolver jpaPhysicalMappingResolver(EntityManagerFactory emf) {
        return new HibernatePhysicalMappingResolver(emf);
    }

    @Bean
    @ConditionalOnMissingBean
    public JpaModelSpaceSource jpaModelSpaceSource(
        JpaPhysicalMappingResolver mappingResolver,
        EntityManagerFactory emf
    ) {
        return new JpaModelSpaceSource(mappingResolver, emf);
    }
}
