module rorm.rorm.springai.main {
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires io.hypersistence.utils.hibernate.type;
    requires jakarta.persistence;
    requires jakarta.validation;
    requires java.compiler;
    requires static lombok;
    requires static org.slf4j;
    requires org.hibernate.orm.core;
    requires org.jspecify;
    requires reactor.core;
    requires rorm.rorm.core;
    requires rorm.rorm.dataimport;
    requires rorm.rorm.serialization;
    requires spring.beans;
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.context;
    requires spring.core;
    requires spring.data.commons;
    requires spring.data.jpa;
    requires spring.data.redis;
    requires spring.tx;
    requires spring.web;
    requires org.jooq;
    requires java.net.http;
}
