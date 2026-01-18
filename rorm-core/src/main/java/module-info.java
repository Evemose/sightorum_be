open module rorm.rorm.core {
    requires static lombok;
    requires org.jooq;
    requires org.jspecify;
    requires spring.context;
    requires spring.boot.autoconfigure;
    requires spring.core;
    requires spring.beans;

    exports com.rorm.query;
    exports com.rorm.engine;
    exports com.rorm.metamodel;
}