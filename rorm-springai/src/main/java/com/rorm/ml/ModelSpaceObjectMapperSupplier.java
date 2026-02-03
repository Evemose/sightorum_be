package com.rorm.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.metamodel.Attribute;
import com.rorm.metamodel.CollectionAttribute.CollectionElement;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.ReferenceAttribute.ReferenceMapping;
import com.rorm.metamodel.Root;
import com.rorm.serialization.metamodel.ModelSpaceSerializationMixins.*;
import io.hypersistence.utils.hibernate.type.util.ObjectMapperSupplier;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

public class ModelSpaceObjectMapperSupplier implements ObjectMapperSupplier, ApplicationContextAware {

    private static Jackson2ObjectMapperBuilder springMapperBuilder;

    @Override
    public ObjectMapper get() {
        return springMapperBuilder
            .mixIn(Root.class, RootMixin.class)
            .mixIn(Attribute.class, AttributeMixin.class)
            .mixIn(DataType.class, DataTypeMixin.class)
            .mixIn(ReferenceMapping.class, ReferenceMappingMixin.class)
            .mixIn(CollectionElement.class, CollectionElementTypeMixin.class)
            .build();
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        springMapperBuilder = applicationContext.getBean(Jackson2ObjectMapperBuilder.class);
    }
}
