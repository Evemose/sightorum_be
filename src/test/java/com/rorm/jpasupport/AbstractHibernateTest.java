package com.rorm.jpasupport;

import com.rorm.jpasupport.testentities.Customer;
import com.rorm.jpasupport.testentities.CustomerProfile;
import com.rorm.jpasupport.testentities.Order;
import com.rorm.metamodel.ModelSpace;
import com.rorm.metamodel.Root;
import com.rorm.testutil.AbstractPostgresTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.junit.jupiter.api.BeforeAll;

import java.sql.DriverManager;
import java.util.HashMap;

@SuppressWarnings("SqlNoDataSourceInspection")
public abstract class AbstractHibernateTest extends AbstractPostgresTest {

    protected static ModelSpace modelSpace;
    protected static Root customerRoot;
    protected static Root orderRoot;
    protected static Root profileRoot;

    protected EntityManagerFactory emf;
    protected EntityManager em;

    @BeforeAll
    static void setupMetamodel() throws Exception {
        var tempSchema = "temp_metamodel";
        try (var conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA " + tempSchema);
        }

        var properties = createHibernateProperties("create-drop", tempSchema);

        var tempEmf = Persistence.createEntityManagerFactory("test-unit", properties);

        var mappingResolver = new HibernatePhysicalMappingResolver(tempEmf);
        var modelSpaceSource = new JpaModelSpaceSource(mappingResolver, tempEmf);
        modelSpace = modelSpaceSource.extractFromInjectedEMF();
        tempEmf.close();

        customerRoot = findRoot(modelSpace, Customer.class);
        orderRoot = findRoot(modelSpace, Order.class);
        profileRoot = findRoot(modelSpace, CustomerProfile.class);

        try (var conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA " + tempSchema + " CASCADE");
        }
    }

    protected static Root findRoot(ModelSpace modelSpace, Class<?> entityClass) {
        // Convert class name to expected table name using snake_case convention
        var expectedTableName = camelCaseToSnakeCase(entityClass.getSimpleName()) + "s";
        return modelSpace.roots().stream()
            .filter(root -> root.primaryTableName().equals(expectedTableName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Root not found for " + entityClass.getSimpleName() + " (expected table: " + expectedTableName + ")"));
    }

    private static String camelCaseToSnakeCase(String input) {
        return input.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    @Override
    protected void afterDatabaseSetup() {
        var properties = createHibernateProperties("create", getCurrentSchema());
        emf = Persistence.createEntityManagerFactory("test-unit", properties);
        em = emf.createEntityManager();
    }

    private static HashMap<String, String> createHibernateProperties(String create, String schemaName) {
        var properties = new HashMap<String, String>();
        properties.put("jakarta.persistence.jdbc.driver", "org.postgresql.Driver");
        properties.put("jakarta.persistence.jdbc.url", postgres.getJdbcUrl());
        properties.put("jakarta.persistence.jdbc.user", postgres.getUsername());
        properties.put("jakarta.persistence.jdbc.password", postgres.getPassword());
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        properties.put("hibernate.hbm2ddl.auto", create);
        properties.put("hibernate.show_sql", "false");
        properties.put("hibernate.format_sql", "true");
        properties.put("hibernate.physical_naming_strategy", CamelCaseToUnderscoresNamingStrategy.class.getName());
        properties.put("hibernate.default_schema", schemaName);
        return properties;
    }

    @Override
    protected void beforeDatabaseCleanup() {
        if (em != null) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            em.close();
        }

        if (emf != null) {
            emf.close();
        }
    }
}
