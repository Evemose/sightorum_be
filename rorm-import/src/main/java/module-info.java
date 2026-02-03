import org.jspecify.annotations.NullMarked;

@NullMarked
open module rorm.rorm.dataimport {
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.csv;
    requires static lombok;
    requires rorm.rorm.core;
    requires spring.context;
    requires spring.jdbc;
    requires spring.tx;
    requires spring.batch.infrastructure;
    requires org.jspecify;
    requires spring.batch.core;
    requires spring.core;
    requires spring.boot.autoconfigure;
    requires org.slf4j;
    requires spring.beans;
    requires java.sql;
    requires com.github.benmanes.caffeine;

    exports com.rorm.dataimport.override;
    exports com.rorm.dataimport.pipeline;
    exports com.rorm.dataimport.source;
}