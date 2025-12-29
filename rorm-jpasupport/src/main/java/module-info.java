open module rorm.rorm.jpasupport {
    requires jakarta.persistence;
    requires static lombok;
    requires org.hibernate.orm.core;
    requires rorm.rorm.core;
    requires spring.boot.autoconfigure;
    requires spring.context;
    requires java.naming;

    exports com.rorm.jpasupport;
}