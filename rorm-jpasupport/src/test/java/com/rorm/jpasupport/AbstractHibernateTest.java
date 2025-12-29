package com.rorm.jpasupport;

import com.rorm.jpasupport.testentities.Customer;
import com.rorm.metamodel.ModelSpace;
import com.rorm.metamodel.Root;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackageClasses = Customer.class)
public abstract class AbstractHibernateTest {

    @Container
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");
    @Autowired
    protected EntityManagerFactory emf;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    protected ModelSpace getModelSpace() {
        var mappingResolver = new HibernatePhysicalMappingResolver(emf);
        var modelSpaceSource = new JpaModelSpaceSource(mappingResolver, emf);
        return modelSpaceSource.extractFromInjectedEMF();
    }

    protected Root findRoot(ModelSpace modelSpace, Class<?> entityClass) {
        var expectedTableName = camelCaseToSnakeCase(entityClass.getSimpleName()) + "s";
        return modelSpace.roots().stream()
            .filter(root -> root.primaryTableName().equals(expectedTableName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Root not found for " + entityClass.getSimpleName() + " (expected table: " + expectedTableName + ")"));
    }

    private String camelCaseToSnakeCase(String input) {
        return input.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
