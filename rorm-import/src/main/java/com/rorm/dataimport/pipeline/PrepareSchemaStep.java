package com.rorm.dataimport.pipeline;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

@Component
@Order(4)
@RequiredArgsConstructor
public class PrepareSchemaStep implements ImportStep<ImportRequest, PreparedImport> {

    private final JdbcTemplate jdbcTemplate;
    private final SchemaGenerator schemaGenerator;
    private final MetamodelConverter metamodelConverter;
    private final TransactionTemplate transactionTemplate;

    @Override
    public PreparedImport execute(ImportRequest request) {
        var modelSpace = Objects.requireNonNull(transactionTemplate.execute(_ -> {
            createSchema(request.targetSchema());
            var ms = metamodelConverter.convertToModelSpace(request.detectedSchema());
            createAllTables(request.targetSchema(), ms);
            return ms;
        }));
        return new PreparedImport(request, modelSpace);
    }

    private void createSchema(String schemaName) {
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + quoteIdentifier(schemaName));
    }

    private void createAllTables(String schemaName, com.rorm.metamodel.ModelSpace modelSpace) {
        var ddlStatements = schemaGenerator.generateAllTablesDdl(schemaName, modelSpace);
        for (var ddl : ddlStatements) {
            jdbcTemplate.execute(ddl);
        }
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
