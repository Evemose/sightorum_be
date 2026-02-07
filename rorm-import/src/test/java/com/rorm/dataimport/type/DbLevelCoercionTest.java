package com.rorm.dataimport.type;

import com.rorm.metamodel.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbLevelCoercionTest {

    @Test
    @DisplayName("ForwardFill should generate correct SQL")
    void forwardFillShouldGenerateCorrectSql() {
        var strategy = DbLevelCoercion.ForwardFill.INSTANCE;
        var sql = strategy.generateSql("test_schema", "users", "score",
            new DataType.NumericType(10, 2), "id");

        assertThat(sql).contains("UPDATE \"test_schema\".\"users\" target");
        assertThat(sql).contains("SET \"score\" =");
        assertThat(sql).contains("SELECT \"score\"");
        assertThat(sql).contains("WHERE source.\"score\" IS NOT NULL");
        assertThat(sql).contains("AND source.\"id\" < target.\"id\"");
        assertThat(sql).contains("ORDER BY source.\"id\" DESC");
        assertThat(sql).contains("LIMIT 1");
        assertThat(sql).contains("WHERE target.\"score\" IS NULL");
    }

    @Test
    @DisplayName("BackwardFill should generate correct SQL")
    void backwardFillShouldGenerateCorrectSql() {
        var strategy = DbLevelCoercion.BackwardFill.INSTANCE;
        var sql = strategy.generateSql("test_schema", "users", "score",
            new DataType.NumericType(10, 2), "id");

        assertThat(sql).contains("UPDATE \"test_schema\".\"users\" target");
        assertThat(sql).contains("SET \"score\" =");
        assertThat(sql).contains("SELECT \"score\"");
        assertThat(sql).contains("WHERE source.\"score\" IS NOT NULL");
        assertThat(sql).contains("AND source.\"id\" > target.\"id\"");
        assertThat(sql).contains("ORDER BY source.\"id\" ASC");
        assertThat(sql).contains("LIMIT 1");
        assertThat(sql).contains("WHERE target.\"score\" IS NULL");
    }

    @Test
    @DisplayName("UseMean for numeric should generate AVG SQL")
    void useMeanForNumericShouldGenerateAvgSql() {
        var strategy = DbLevelCoercion.UseMean.INSTANCE;
        var sql = strategy.generateSql("test_schema", "users", "score",
            new DataType.NumericType(10, 2), "id");

        assertThat(sql).contains("UPDATE \"test_schema\".\"users\"");
        assertThat(sql).contains("SET \"score\" =");
        assertThat(sql).contains("SELECT AVG(\"score\")");
        assertThat(sql).contains("WHERE \"score\" IS NOT NULL");
        assertThat(sql).contains("WHERE \"score\" IS NULL");
    }

    @Test
    @DisplayName("UseMean for text should generate MODE SQL")
    void useMeanForTextShouldGenerateModeSql() {
        var strategy = DbLevelCoercion.UseMean.INSTANCE;
        var sql = strategy.generateSql("test_schema", "users", "status",
            new DataType.StringType(), "id");

        assertThat(sql).contains("UPDATE \"test_schema\".\"users\"");
        assertThat(sql).contains("SET \"status\" =");
        assertThat(sql).contains("SELECT \"status\"");
        assertThat(sql).contains("WHERE \"status\" IS NOT NULL");
        assertThat(sql).contains("GROUP BY \"status\"");
        assertThat(sql).contains("ORDER BY COUNT(*) DESC");
        assertThat(sql).contains("LIMIT 1");
        assertThat(sql).contains("WHERE \"status\" IS NULL");
    }

    @Test
    @DisplayName("UseMedian for numeric should generate PERCENTILE_CONT SQL")
    void useMedianForNumericShouldGeneratePercentileSql() {
        var strategy = DbLevelCoercion.UseMedian.INSTANCE;
        var sql = strategy.generateSql("test_schema", "users", "score",
            new DataType.NumericType(10, 2), "id");

        assertThat(sql).contains("UPDATE \"test_schema\".\"users\"");
        assertThat(sql).contains("SET \"score\" =");
        assertThat(sql).contains("PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY \"score\")");
        assertThat(sql).contains("WHERE \"score\" IS NOT NULL");
        assertThat(sql).contains("WHERE \"score\" IS NULL");
    }

    @Test
    @DisplayName("UseMedian for text should throw exception")
    void useMedianForTextShouldThrow() {
        var strategy = DbLevelCoercion.UseMedian.INSTANCE;

        assertThatThrownBy(() ->
            strategy.generateSql("test_schema", "users", "status",
                new DataType.StringType(), "id")
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("UseMedian only applicable to numeric columns");
    }

    @Test
    @DisplayName("UseMode should generate MODE SQL")
    void useModeShouldGenerateModeSql() {
        var strategy = DbLevelCoercion.UseMode.INSTANCE;
        var sql = strategy.generateSql("test_schema", "users", "status",
            new DataType.StringType(), "id");

        assertThat(sql).contains("UPDATE \"test_schema\".\"users\"");
        assertThat(sql).contains("SET \"status\" =");
        assertThat(sql).contains("SELECT \"status\"");
        assertThat(sql).contains("WHERE \"status\" IS NOT NULL");
        assertThat(sql).contains("GROUP BY \"status\"");
        assertThat(sql).contains("ORDER BY COUNT(*) DESC");
        assertThat(sql).contains("LIMIT 1");
        assertThat(sql).contains("WHERE \"status\" IS NULL");
    }
}
