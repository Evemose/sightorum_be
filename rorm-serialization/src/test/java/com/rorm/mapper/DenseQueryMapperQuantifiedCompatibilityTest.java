package com.rorm.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.dto.dense.DenseQueryDto;
import com.rorm.metamodel.*;
import com.rorm.query.Expression;
import com.rorm.query.StandardOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Dense quantified compatibility mapping")
class DenseQueryMapperQuantifiedCompatibilityTest {

    private static QueryMapper queryMapper;
    private static DenseQueryMapper denseMapper;
    private static ObjectMapper objectMapper;
    private static ModelSpace modelSpace;

    @BeforeAll
    static void setup() throws Exception {
        queryMapper = Mappers.getMapper(QueryMapper.class);
        var field = QueryMapper.class.getDeclaredField("pathResolver");
        field.setAccessible(true);
        field.set(queryMapper, new PathResolver());

        denseMapper = new DenseQueryMapper(queryMapper);
        objectMapper = new ObjectMapper();

        var empId = new BasicAttribute("id", new AttributeLocation("employees", "id"), new DataType.NumericType(19, 0));
        var empSalary = new BasicAttribute("salary", new AttributeLocation("employees", "salary"), new DataType.NumericType(10, 2));
        var employeeRoot = new Root("employees", List.of(empId, empSalary), IdDescriptor.longId("employees"));

        var deptId = new BasicAttribute("id", new AttributeLocation("departments", "id"), new DataType.NumericType(19, 0));
        var deptBudget = new BasicAttribute("budget", new AttributeLocation("departments", "budget"), new DataType.NumericType(15, 2));
        var departmentRoot = new Root("departments", List.of(deptId, deptBudget), IdDescriptor.longId("departments"));

        modelSpace = new ModelSpace(Set.of(employeeRoot, departmentRoot));
    }

    @Test
    @DisplayName("accepts quantified JSON with comparison + subquery wrapper")
    void quantifiedCompatibilityShape() throws Exception {
        var json = """
            {
              "from": "employees",
              "fromAlias": "e",
              "selector": {
                "@type": "single",
                "expression": {"@type": "path", "path": "id"},
                "distinct": false,
                "alias": "id"
              },
              "where": {
                "@type": "quantified",
                "left": {"@type": "path", "path": "salary"},
                "comparison": "GREATER_THAN",
                "quantifier": "ANY",
                "subquery": {
                  "@type": "subquery",
                  "query": {
                    "from": "departments",
                    "fromAlias": "d",
                    "selector": {
                      "@type": "single",
                      "expression": {"@type": "literal", "value": 1},
                      "distinct": false,
                      "alias": "v"
                    }
                  }
                }
              }
            }
            """;

        var dense = objectMapper.readValue(json, DenseQueryDto.class);
        var query = denseMapper.toEntity(dense, modelSpace);

        assertThat(query.where()).isInstanceOf(Expression.QuantifiedComparison.class);
        var quantified = (Expression.QuantifiedComparison) query.where();
        assertThat(quantified.comparison()).isEqualTo(StandardOperator.Binary.GREATER_THAN);
        assertThat(quantified.quantifier()).isEqualTo(Expression.QuantifiedComparison.Quantifier.ANY);
        assertThat(quantified.subquery().query().from().root().primaryTableName()).isEqualTo("departments");
        assertThat(quantified.subquery().query().from().alias()).isEqualTo("d");
    }

    @Test
    @DisplayName("accepts quantified JSON with direct query payload and resolves subquery-local paths")
    void quantifiedDirectQueryWithLocalPathResolution() throws Exception {
        var json = """
            {
              "from": "employees",
              "fromAlias": "e",
              "selector": {
                "@type": "single",
                "expression": {"@type": "path", "path": "id"},
                "distinct": false,
                "alias": "id"
              },
              "where": {
                "@type": "quantified",
                "left": {"@type": "path", "path": "salary"},
                "operator": "LESS_THAN",
                "quantifier": "ALL",
                "query": {
                  "from": "departments",
                  "fromAlias": "d",
                  "selector": {
                    "@type": "single",
                    "expression": {"@type": "path", "path": "budget"},
                    "distinct": false,
                    "alias": "budget"
                  },
                  "where": {
                    "@type": "binary",
                    "left": {"@type": "path", "path": "budget"},
                    "operator": "GREATER_THAN",
                    "right": {"@type": "literal", "value": 0}
                  }
                }
              }
            }
            """;

        var dense = objectMapper.readValue(json, DenseQueryDto.class);
        var query = denseMapper.toEntity(dense, modelSpace);

        assertThat(query.where()).isInstanceOf(Expression.QuantifiedComparison.class);
        var quantified = (Expression.QuantifiedComparison) query.where();
        assertThat(quantified.comparison()).isEqualTo(StandardOperator.Binary.LESS_THAN);
        assertThat(quantified.quantifier()).isEqualTo(Expression.QuantifiedComparison.Quantifier.ALL);
        assertThat(quantified.subquery().query().where()).isInstanceOf(Expression.BinaryExpression.class);
    }
}




