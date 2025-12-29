package com.rorm.config;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.CollectionAttribute.CollectionElement;
import com.rorm.metamodel.CollectionAttribute.CompositeElement;
import com.rorm.metamodel.ReferenceAttribute.InverseRootTableColumn;
import com.rorm.metamodel.ReferenceAttribute.JoinTableMapping;
import com.rorm.metamodel.ReferenceAttribute.ReferenceMapping;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetamodelJacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer metamodelJacksonCustomizer() {
        return builder -> builder.modulesToInstall(modules ->
            modules.add(createMetamodelModule())
        );
    }

    private Module createMetamodelModule() {
        var module = new SimpleModule("MetamodelModule");

        // Handle circular references via identity
        module.setMixInAnnotation(Root.class, RootMixin.class);

        // Polymorphic type handling
        module.setMixInAnnotation(Attribute.class, AttributeMixin.class);
        module.setMixInAnnotation(PathTarget.class, PathTargetMixin.class);
        module.setMixInAnnotation(ReferenceMapping.class, ReferenceMappingMixin.class);
        module.setMixInAnnotation(CollectionElement.class, CollectionElementMixin.class);

        return module;
    }

    @JsonIdentityInfo(generator = ObjectIdGenerators.StringIdGenerator.class)
    abstract static class RootMixin {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = BasicAttribute.class, name = "basic"),
        @JsonSubTypes.Type(value = CompositeAttribute.class, name = "composite"),
        @JsonSubTypes.Type(value = SingularReferenceAttribute.class, name = "singularRef"),
        @JsonSubTypes.Type(value = PluralReferenceAttribute.class, name = "pluralRef"),
        @JsonSubTypes.Type(value = CollectionAttribute.class, name = "collection")
    })
    abstract static class AttributeMixin {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = BasicAttribute.class, name = "basic"),
        @JsonSubTypes.Type(value = CompositeAttribute.class, name = "composite"),
        @JsonSubTypes.Type(value = SingularReferenceAttribute.class, name = "singularRef"),
        @JsonSubTypes.Type(value = PluralReferenceAttribute.class, name = "pluralRef"),
        @JsonSubTypes.Type(value = CollectionAttribute.class, name = "collection"),
        @JsonSubTypes.Type(value = BasicElement.class, name = "basicElement"),
        @JsonSubTypes.Type(value = CompositeElement.class, name = "compositeElement")
    })
    abstract static class PathTargetMixin {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = InverseRootTableColumn.class, name = "inverseColumn"),
        @JsonSubTypes.Type(value = JoinTableMapping.class, name = "joinTable")
    })
    abstract static class ReferenceMappingMixin {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = BasicElement.class, name = "basicElement"),
        @JsonSubTypes.Type(value = CompositeElement.class, name = "compositeElement")
    })
    abstract static class CollectionElementMixin {
    }
}
