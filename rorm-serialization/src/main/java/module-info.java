open module rorm.rorm.serialization {
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.databind;
    requires rorm.rorm.core;
    requires spring.boot.autoconfigure;
    requires spring.context;
    requires spring.web;

    exports com.rorm.config;
}