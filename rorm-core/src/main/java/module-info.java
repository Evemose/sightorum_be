open module rorm.rorm.core {
    requires static lombok;
    requires org.jooq;
    requires org.jspecify;
    requires spring.beans;
    requires spring.context;

    exports com.rorm.query;
    exports com.rorm.engine;
    exports com.rorm.metamodel;
}