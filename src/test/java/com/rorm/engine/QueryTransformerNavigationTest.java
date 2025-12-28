package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.ReferenceAttribute.InverseRootTableColumn;
import com.rorm.metamodel.ReferenceAttribute.JoinTableMapping;
import com.rorm.query.Expression.BinaryExpression;
import com.rorm.query.Expression.FunctionCall;
import com.rorm.query.Expression.Literal;
import com.rorm.query.*;
import com.rorm.query.Operator.BinaryOperator;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.RootSelector;
import com.rorm.query.Selector.SingleExprSelector;
import com.rorm.testutil.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class QueryTransformerNavigationTest extends AbstractPostgresTest {

    private static Root customerRoot;
    private static Root profileRoot;
    private static Root addressRoot;
    private static Root orderRoot;

    private static BasicAttribute customerId;
    private static BasicAttribute customerName;
    private static BasicAttribute customerEmail;
    private static SingularReferenceAttribute customerProfile;

    private static BasicAttribute profileId;
    private static BasicAttribute profileBio;
    private static SingularReferenceAttribute profileAddress;
    private static CompositeAttribute profileSocialLinks;

    private static BasicAttribute addressId;
    private static BasicAttribute addressCity;
    private static BasicAttribute addressCountry;

    private static BasicAttribute socialLinksTwitter;
    private static BasicAttribute socialLinksLinkedin;

    private static BasicAttribute orderId;
    private static BasicAttribute orderNumber;
    private static BasicAttribute orderAmount;
    private static SingularReferenceAttribute orderCustomer;

    private static PluralReferenceAttribute customerOrders;
    private static CollectionAttribute orderTags;
    private static BasicElement orderTagElement;

    private QueryTransformer transformer;

    @BeforeAll
    static void setupMetamodel() {
        // Build roots in order of dependencies (no circular refs in this test model)

        // Address (no dependencies)
        addressId = new BasicAttribute("id", new AttributeLocation("addresses", "id"));
        addressCity = new BasicAttribute("city", new AttributeLocation("addresses", "city"));
        addressCountry = new BasicAttribute("country", new AttributeLocation("addresses", "country"));
        addressRoot = new Root("addresses", List.of(addressId, addressCity, addressCountry));

        // Profile (depends on Address)
        profileId = new BasicAttribute("id", new AttributeLocation("profiles", "id"));
        profileBio = new BasicAttribute("bio", new AttributeLocation("profiles", "bio"));
        profileAddress = new SingularReferenceAttribute("address", addressRoot,
            new JoinTableMapping(new AttributeLocation("profiles", "address_id"), "id"));
        socialLinksTwitter = new BasicAttribute("twitter", new AttributeLocation("profiles", "twitter"));
        socialLinksLinkedin = new BasicAttribute("linkedin", new AttributeLocation("profiles", "linkedin"));
        profileSocialLinks = new CompositeAttribute("socialLinks", Set.of(socialLinksTwitter, socialLinksLinkedin));
        profileRoot = new Root("profiles", List.of(profileId, profileBio, profileAddress, profileSocialLinks));

        // Order (will be completed after Customer for circular ref)
        orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"));
        orderNumber = new BasicAttribute("number", new AttributeLocation("orders", "number"));
        orderAmount = new BasicAttribute("amount", new AttributeLocation("orders", "amount"));
        orderTagElement = new BasicElement(new AttributeLocation("order_tags", "tag"));
        orderTags = new CollectionAttribute("tags", "order_tags", orderTagElement);

        // Customer (depends on Profile, Order)
        customerId = new BasicAttribute("id", new AttributeLocation("customers", "id"));
        customerName = new BasicAttribute("name", new AttributeLocation("customers", "name"));
        customerEmail = new BasicAttribute("email", new AttributeLocation("customers", "email"));
        customerProfile = new SingularReferenceAttribute("profile", profileRoot,
            new JoinTableMapping(new AttributeLocation("customers", "profile_id"), "id"));

        // Create orderRoot first without orderCustomer to break circular dependency
        orderRoot = new Root("orders", List.of(orderId, orderNumber, orderAmount, orderTags));

        // Now create customerOrders pointing to orderRoot (OneToMany mappedBy - FK on orders table)
        customerOrders = new PluralReferenceAttribute("orders", orderRoot, new InverseRootTableColumn("customer_id"));
        customerRoot = new Root("customers", List.of(customerId, customerName, customerEmail, customerProfile, customerOrders));

        // Create orderCustomer pointing to customerRoot and rebuild orderRoot
        orderCustomer = new SingularReferenceAttribute("customer", customerRoot,
            new JoinTableMapping(new AttributeLocation("orders", "customer_id"), "id"));
        orderRoot = new Root("orders", List.of(orderId, orderNumber, orderAmount, orderCustomer, orderTags));
    }

    @Override
    protected void afterDatabaseSetup() throws Exception {
        dsl.execute("""
            create table customers (
                id bigserial primary key,
                name varchar(255),
                email varchar(255),
                profile_id bigint
            )
            """);

        dsl.execute("""
            create table profiles (
                id bigserial primary key,
                bio text,
                address_id bigint,
                twitter varchar(255),
                linkedin varchar(255)
            )
            """);

        dsl.execute("""
            create table addresses (
                id bigserial primary key,
                city varchar(255),
                country varchar(255)
            )
            """);

        dsl.execute("""
            create table orders (
                id bigserial primary key,
                number varchar(255),
                amount double precision,
                customer_id bigint
            )
            """);

        dsl.execute("""
            create table order_tags (
                order_id bigint,
                tag varchar(255)
            )
            """);


        dsl.execute("insert into addresses (id, city, country) values (1, 'New York', 'USA')");
        dsl.execute("insert into addresses (id, city, country) values (2, 'London', 'UK')");
        dsl.execute("insert into profiles (id, bio, address_id, twitter, linkedin) values (1, 'Software Engineer', 1, '@john_dev', 'john-doe')");
        dsl.execute("insert into profiles (id, bio, address_id, twitter, linkedin) values (2, 'Data Scientist', 2, '@jane_data', 'jane-smith')");
        dsl.execute("insert into customers (id, name, email, profile_id) values (1, 'John Doe', 'john@example.com', 1)");
        dsl.execute("insert into customers (id, name, email, profile_id) values (2, 'Jane Smith', 'jane@example.com', 2)");
        dsl.execute("insert into orders (id, number, amount, customer_id) values (1, 'ORD-001', 100.50, 1)");
        dsl.execute("insert into orders (id, number, amount, customer_id) values (2, 'ORD-002', 250.75, 1)");
        dsl.execute("insert into orders (id, number, amount, customer_id) values (3, 'ORD-003', 500.00, 2)");

        dsl.execute("insert into order_tags (order_id, tag) values (1, 'urgent')");
        dsl.execute("insert into order_tags (order_id, tag) values (1, 'domestic')");
        dsl.execute("insert into order_tags (order_id, tag) values (2, 'international')");
        dsl.execute("insert into order_tags (order_id, tag) values (3, 'urgent')");
        dsl.execute("insert into order_tags (order_id, tag) values (3, 'international')");

        transformer = new QueryTransformer(dsl, new ExpressionTransformer());
    }

    @Test
    @DisplayName("Should navigate single level relationship in WHERE clause")
    void shouldNavigateSingleLevelInWhere() {
        var profileBioPath = new Path(profileBio, new Path(customerProfile, null));

        var query = Query.builder()
            .from(customerRoot)
            .selector(new RootSelector(customerRoot, false))
            .joins(new LinkedHashSet<>())
            .where(new BinaryExpression(
                profileBioPath,
                BinaryOperator.EQUALS,
                new Literal("Software Engineer")
            ))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result)
            .singleElement()
            .extracting(r -> r.get("name"))
            .isEqualTo("John Doe");
    }

    @Test
    @DisplayName("Should navigate two level relationship in WHERE clause")
    void shouldNavigateTwoLevelsInWhere() {
        var addressCityPath = new Path(addressCity,
            new Path(profileAddress,
                new Path(customerProfile, null)));

        var query = Query.builder()
            .from(customerRoot)
            .selector(new RootSelector(customerRoot, false))
            .joins(new LinkedHashSet<>())
            .where(new BinaryExpression(
                addressCityPath,
                BinaryOperator.EQUALS,
                new Literal("London")
            ))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result)
            .singleElement()
            .extracting(r -> r.get("name"))
            .isEqualTo("Jane Smith");
    }

    @Test
    @DisplayName("Should navigate relationship in SELECT clause")
    void shouldNavigateInSelect() {
        var profileBioPath = new Path(profileBio, new Path(customerProfile, null));

        var query = Query.builder()
            .from(customerRoot)
            .selector(new SingleExprSelector(profileBioPath, false, "bio"))
            .joins(new LinkedHashSet<>())
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result)
            .hasSize(2)
            .extracting(r -> r.get("bio"))
            .containsExactlyInAnyOrder("Software Engineer", "Data Scientist");
    }

    @Test
    @DisplayName("Should navigate relationship in GROUP BY clause")
    void shouldNavigateInGroupBy() {
        var customerIdPath = new Path(customerId, new Path(orderCustomer, null));

        var countExpr = new FunctionCall("COUNT", List.of(new Literal("*")));

        var query = Query.builder()
            .from(orderRoot)
            .selector(new MultiExprSelector(
                Set.of(
                    new SelectedExpression(customerIdPath, "customer_id"),
                    new SelectedExpression(countExpr, "order_count")
                ),
                false
            ))
            .joins(new LinkedHashSet<>())
            .groupBy(new GroupBy(customerIdPath))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result).hasSize(2);

        assertThat(result)
            .filteredOn(r -> Long.valueOf(1).equals(r.get("customer_id")))
            .singleElement()
            .extracting(r -> r.get("order_count"))
            .asInstanceOf(LONG)
            .isEqualTo(2L);

        assertThat(result)
            .filteredOn(r -> Long.valueOf(2).equals(r.get("customer_id")))
            .singleElement()
            .extracting(r -> r.get("order_count"))
            .asInstanceOf(LONG)
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("Should navigate relationship in ORDER BY clause")
    void shouldNavigateInOrderBy() {
        var profileBioPath = new Path(profileBio, new Path(customerProfile, null));

        var query = Query.builder()
            .from(customerRoot)
            .selector(new RootSelector(customerRoot, false))
            .joins(new LinkedHashSet<>())
            .orderBy(OrderBy.asc(profileBioPath))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("name")).isEqualTo("Jane Smith");
        assertThat(result.get(1).get("name")).isEqualTo("John Doe");
    }

    @Test
    @DisplayName("Should navigate relationship in HAVING clause")
    void shouldNavigateInHaving() {
        var customerIdPath = new Path(customerId, new Path(orderCustomer, null));
        var countExpr = new FunctionCall("COUNT", List.of(new Literal("*")));

        var query = Query.builder()
            .from(orderRoot)
            .selector(new MultiExprSelector(
                Set.of(
                    new SelectedExpression(customerIdPath, "customer_id"),
                    new SelectedExpression(countExpr, "order_count")
                ),
                false
            ))
            .joins(new LinkedHashSet<>())
            .groupBy(new GroupBy(customerIdPath))
            .having(new BinaryExpression(
                countExpr,
                BinaryOperator.GREATER_THAN,
                new Literal(1)
            ))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result)
            .singleElement()
            .satisfies(record -> {
                assertThat(record.get("customer_id")).isEqualTo(1L);
                assertThat(record.get("order_count"))
                    .asInstanceOf(LONG)
                    .isEqualTo(2L);
            });
    }

    @Test
    @DisplayName("Should navigate relationship within aggregate function")
    void shouldNavigateInAggregateFunction() {
        var customerNamePath = new Path(customerName, new Path(orderCustomer, null));
        var maxNameExpr = new FunctionCall("MAX", List.of(customerNamePath));

        var query = Query.builder()
            .from(orderRoot)
            .selector(new SingleExprSelector(maxNameExpr, false, "max_customer_name"))
            .joins(new LinkedHashSet<>())
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result)
            .singleElement()
            .extracting(r -> r.get("max_customer_name"))
            .isEqualTo("John Doe");
    }

    @Test
    @DisplayName("Should navigate relationship within nested function calls")
    void shouldNavigateInNestedFunctions() {
        var customerEmailPath = new Path(customerEmail, new Path(orderCustomer, null));
        var lowerExpr = new FunctionCall("LOWER", List.of(customerEmailPath));
        var substringExpr = new FunctionCall("SUBSTRING", List.of(lowerExpr, new Literal(1), new Literal(4)));

        var query = Query.builder()
            .from(orderRoot)
            .selector(new SingleExprSelector(substringExpr, false, "email_prefix"))
            .joins(new LinkedHashSet<>())
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result)
            .hasSize(3)
            .extracting(r -> r.get("email_prefix"))
            .contains("john", "jane");
    }

    @Test
    @DisplayName("Should navigate multiple paths in complex WHERE expression")
    void shouldNavigateMultiplePathsInWhere() {
        var profileBioPath = new Path(profileBio, new Path(customerProfile, null));
        var addressCityPath = new Path(addressCity,
            new Path(profileAddress,
                new Path(customerProfile, null)));

        var bioCondition = new BinaryExpression(
            profileBioPath,
            BinaryOperator.EQUALS,
            new Literal("Software Engineer")
        );

        var cityCondition = new BinaryExpression(
            addressCityPath,
            BinaryOperator.EQUALS,
            new Literal("New York")
        );

        var query = Query.builder()
            .from(customerRoot)
            .selector(new RootSelector(customerRoot, false))
            .joins(new LinkedHashSet<>())
            .where(new BinaryExpression(
                bioCondition,
                BinaryOperator.AND,
                cityCondition
            ))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result)
            .singleElement()
            .extracting(r -> r.get("name"))
            .isEqualTo("John Doe");
    }

    @Test
    @DisplayName("Should navigate paths in SELECT with multiple expressions")
    void shouldNavigateInMultipleSelectExpressions() {
        var profileBioPath = new Path(profileBio, new Path(customerProfile, null));
        var addressCityPath = new Path(addressCity,
            new Path(profileAddress,
                new Path(customerProfile, null)));

        var query = Query.builder()
            .from(customerRoot)
            .selector(new MultiExprSelector(
                Set.of(
                    new SelectedExpression(new Path(customerName, null), "name"),
                    new SelectedExpression(profileBioPath, "bio"),
                    new SelectedExpression(addressCityPath, "city")
                ),
                false
            ))
            .joins(new LinkedHashSet<>())
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result).hasSize(2);

        assertThat(result)
            .filteredOn(r -> "John Doe".equals(r.get("name")))
            .singleElement()
            .satisfies(record -> {
                assertThat(record.get("bio")).isEqualTo("Software Engineer");
                assertThat(record.get("city")).isEqualTo("New York");
            });
    }

    @Test
    @DisplayName("Should navigate relationship in ORDER BY with aggregate")
    void shouldNavigateInOrderByWithAggregate() {
        var customerNamePath = new Path(customerName, new Path(orderCustomer, null));
        var customerIdPath = new Path(customerId, new Path(orderCustomer, null));

        var sumExpr = new FunctionCall("SUM", List.of(new Path(orderAmount, null)));
        var maxNameExpr = new FunctionCall("MAX", List.of(customerNamePath));

        var query = Query.builder()
            .from(orderRoot)
            .selector(new MultiExprSelector(
                Set.of(
                    new SelectedExpression(customerIdPath, "customer_id"),
                    new SelectedExpression(maxNameExpr, "customer_name"),
                    new SelectedExpression(sumExpr, "total_amount")
                ),
                false
            ))
            .joins(new LinkedHashSet<>())
            .groupBy(new GroupBy(customerIdPath))
            .orderBy(OrderBy.desc(sumExpr))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("customer_name")).isEqualTo("Jane Smith");
        assertThat(result.get(0).get("total_amount"))
            .asInstanceOf(DOUBLE)
            .isEqualTo(500.00);
    }

    /**
     * Bug: QueryTransformer does not handle navigation through relationship -> embedded -> field.
     * <p>
     * Path structure: customer -> profile (ReferenceAttribute) -> socialLinks (CompositeAttribute) -> twitter (BasicAttribute)
     * <p>
     * Current behavior:
     * - Generates: SELECT ... FROM customers t0 WHERE t0.twitter = '@john_dev'
     * - Fails with: ERROR: column t0.twitter does not exist
     * <p>
     * Expected behavior:
     * - Should generate: SELECT ... FROM customers t0
     * LEFT JOIN profiles t1 ON t0.profile_id = t1.id
     * WHERE t1.twitter = '@john_dev'
     * <p>
     * The bug is that QueryTransformer does not create the necessary JOIN when navigating through
     * a ReferenceAttribute to reach a field inside a CompositeAttribute on the related table.
     */
    @Test
    @DisplayName("Should navigate through relationship into embedded attribute field in WHERE")
    void shouldNavigateThroughRelationshipToEmbeddedField() {
        var twitterPath = new Path(socialLinksTwitter,
            new Path(profileSocialLinks,
                new Path(customerProfile, null)));

        var query = Query.builder()
            .from(customerRoot)
            .selector(new RootSelector(customerRoot, false))
            .joins(new LinkedHashSet<>())
            .where(new BinaryExpression(
                twitterPath,
                BinaryOperator.EQUALS,
                new Literal("@john_dev")
            ))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result)
            .singleElement()
            .extracting(r -> r.get("name"))
            .isEqualTo("John Doe");
    }

    @Test
    @DisplayName("Should navigate through relationship into embedded attribute field in SELECT")
    void shouldSelectThroughRelationshipToEmbeddedField() {
        var twitterPath = new Path(socialLinksTwitter,
            new Path(profileSocialLinks,
                new Path(customerProfile, null)));

        var query = Query.builder()
            .from(customerRoot)
            .selector(new MultiExprSelector(
                Set.of(
                    new SelectedExpression(new Path(customerName, null), "name"),
                    new SelectedExpression(twitterPath, "twitter")
                ),
                false
            ))
            .joins(new LinkedHashSet<>())
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result).hasSize(2);

        assertThat(result)
            .filteredOn(r -> "John Doe".equals(r.get("name")))
            .singleElement()
            .extracting(r -> r.get("twitter"))
            .isEqualTo("@john_dev");

        assertThat(result)
            .filteredOn(r -> "Jane Smith".equals(r.get("name")))
            .singleElement()
            .extracting(r -> r.get("twitter"))
            .isEqualTo("@jane_data");
    }

    @Test
    @DisplayName("Should navigate through relationship into embedded attribute field in ORDER BY")
    void shouldOrderByThroughRelationshipToEmbeddedField() {
        var linkedinPath = new Path(socialLinksLinkedin,
            new Path(profileSocialLinks,
                new Path(customerProfile, null)));

        var query = Query.builder()
            .from(customerRoot)
            .selector(new RootSelector(customerRoot, false))
            .joins(new LinkedHashSet<>())
            .orderBy(OrderBy.asc(linkedinPath))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("name")).isEqualTo("Jane Smith");
        assertThat(result.get(1).get("name")).isEqualTo("John Doe");
    }

    @Test
    @DisplayName("Should navigate relationship in HAVING with complex condition")
    void shouldNavigateInHavingWithComplexCondition() {
        var customerIdPath = new Path(customerId, new Path(orderCustomer, null));
        var customerNamePath = new Path(customerName, new Path(orderCustomer, null));

        var countExpr = new FunctionCall("COUNT", List.of(new Literal("*")));
        var sumExpr = new FunctionCall("SUM", List.of(new Path(orderAmount, null)));
        var maxNameExpr = new FunctionCall("MAX", List.of(customerNamePath));

        var countCondition = new BinaryExpression(
            countExpr,
            BinaryOperator.GREATER_THAN,
            new Literal(1)
        );

        var sumCondition = new BinaryExpression(
            sumExpr,
            BinaryOperator.GREATER_THAN,
            new Literal(200.0)
        );

        var query = Query.builder()
            .from(orderRoot)
            .selector(new MultiExprSelector(
                Set.of(
                    new SelectedExpression(customerIdPath, "customer_id"),
                    new SelectedExpression(maxNameExpr, "customer_name"),
                    new SelectedExpression(countExpr, "order_count"),
                    new SelectedExpression(sumExpr, "total_amount")
                ),
                false
            ))
            .joins(new LinkedHashSet<>())
            .groupBy(new GroupBy(customerIdPath))
            .having(new BinaryExpression(
                countCondition,
                BinaryOperator.AND,
                sumCondition
            ))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result)
            .singleElement()
            .satisfies(record -> {
                assertThat(record.get("customer_name")).isEqualTo("John Doe");
                assertThat(record.get("order_count"))
                    .asInstanceOf(LONG)
                    .isEqualTo(2L);
                assertThat(record.get("total_amount"))
                    .asInstanceOf(DOUBLE)
                    .isGreaterThan(200.0);
            });
    }

    @Test
    // @org.junit.jupiter.api.Disabled("Bug: QueryTransformer does not handle navigation through ref -> multiref -> ref")
    @DisplayName("Should navigate ref -> multiref -> ref (customer -> orders -> customer)")
    void shouldNavigateRefToMultirefToRef() {
        var ordersPath = new Path(customerOrders, null);
        var orderCustomerPath = new Path(orderCustomer, ordersPath);
        var orderCustomerNamePath = new Path(customerName, orderCustomerPath);

        var query = Query.builder()
            .from(customerRoot)
            .selector(new MultiExprSelector(
                Set.of(
                    new SelectedExpression(new Path(customerName, null), "name"),
                    new SelectedExpression(orderCustomerNamePath, "order_customer_name")
                ),
                false
            ))
            .joins(new LinkedHashSet<>())
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result).isNotEmpty();
    }

    @Test
    // @org.junit.jupiter.api.Disabled("Bug: QueryTransformer does not handle navigation through multiref -> ref")
    @DisplayName("Should navigate multiref -> ref in WHERE (filter orders by customer name)")
    void shouldNavigateMultirefToRef() {
        var ordersPath = new Path(customerOrders, null);
        var orderCustomerPath = new Path(orderCustomer, ordersPath);
        var customerNamePath = new Path(customerName, orderCustomerPath);

        var query = Query.builder()
            .from(customerRoot)
            .selector(new RootSelector(customerRoot, false))
            .joins(new LinkedHashSet<>())
            .where(new BinaryExpression(
                customerNamePath,
                BinaryOperator.EQUALS,
                new Literal("John Doe")
            ))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("Should navigate ref -> ref -> ref (customer -> profile -> address -> city)")
    void shouldNavigateThreeLevelReferences() {
        var addressCityPath = new Path(addressCity,
            new Path(profileAddress,
                new Path(customerProfile, null)));

        var query = Query.builder()
            .from(customerRoot)
            .selector(new MultiExprSelector(
                Set.of(
                    new SelectedExpression(new Path(customerName, null), "name"),
                    new SelectedExpression(addressCityPath, "city")
                ),
                false
            ))
            .joins(new LinkedHashSet<>())
            .where(new BinaryExpression(
                addressCityPath,
                BinaryOperator.EQUALS,
                new Literal("New York")
            ))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result)
            .singleElement()
            .satisfies(record -> {
                assertThat(record.get("name")).isEqualTo("John Doe");
                assertThat(record.get("city")).isEqualTo("New York");
            });
    }

    @Test
    @DisplayName("Should navigate CollectionAttribute (order -> tags)")
    void shouldNavigateCollectionAttribute() {
        var tagsPath = new Path(orderTags, null);
        var tagPath = new Path(orderTagElement, tagsPath);

        var query = Query.builder()
            .from(orderRoot)
            .selector(new MultiExprSelector(
                Set.of(
                    new SelectedExpression(new Path(orderNumber, null), "order_number"),
                    new SelectedExpression(tagPath, "tag")
                ),
                false
            ))
            .joins(new LinkedHashSet<>())
            .where(new BinaryExpression(
                tagPath,
                BinaryOperator.EQUALS,
                new Literal("urgent")
            ))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result).hasSize(2);
        assertThat(result)
            .extracting(r -> r.get("order_number"))
            .containsExactlyInAnyOrder("ORD-001", "ORD-003");
    }

    @Test
    @DisplayName("Should navigate ref -> CollectionAttribute (customer -> order -> tags)")
    void shouldNavigateRefToCollectionAttribute() {
        var ordersPath = new Path(customerOrders, null);
        var tagsPath = new Path(orderTags, ordersPath);
        var tagPath = new Path(orderTagElement, tagsPath);

        var query = Query.builder()
            .from(customerRoot)
            .selector(new MultiExprSelector(
                Set.of(
                    new SelectedExpression(new Path(customerName, null), "name"),
                    new SelectedExpression(tagPath, "tag")
                ),
                false
            ))
            .joins(new LinkedHashSet<>())
            .where(new BinaryExpression(
                tagPath,
                BinaryOperator.EQUALS,
                new Literal("international")
            ))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result).isNotEmpty();
    }

    @Test
    // @org.junit.jupiter.api.Disabled("Bug: QueryTransformer does not handle aggregates over PluralReferenceAttribute")
    @DisplayName("Should aggregate over PluralReferenceAttribute (count orders per customer)")
    void shouldAggregateOverPluralReference() {
        var ordersPath = new Path(customerOrders, null);
        var countExpr = new FunctionCall("COUNT", List.of(ordersPath));

        var query = Query.builder()
            .from(customerRoot)
            .selector(new MultiExprSelector(
                Set.of(
                    new SelectedExpression(new Path(customerId, null), "customer_id"),
                    new SelectedExpression(new Path(customerName, null), "name"),
                    new SelectedExpression(countExpr, "order_count")
                ),
                false
            ))
            .joins(new LinkedHashSet<>())
            .groupBy(new GroupBy(new Path(customerId, null)))
            .build();

        var sql = transformer.transform(query);
        var result = dsl.fetch(sql);

        assertThat(result).hasSize(2);
        assertThat(result)
            .filteredOn(r -> "John Doe".equals(r.get("name")))
            .singleElement()
            .extracting(r -> r.get("order_count"))
            .asInstanceOf(LONG)
            .isEqualTo(2L);
    }
}

