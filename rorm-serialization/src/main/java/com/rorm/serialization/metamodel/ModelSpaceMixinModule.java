package com.rorm.serialization.metamodel;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.Module;
import com.rorm.metamodel.Attribute;
import com.rorm.metamodel.CollectionAttribute.CollectionElement;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.ReferenceAttribute.ReferenceMapping;
import com.rorm.metamodel.Root;
import com.rorm.serialization.metamodel.ModelSpaceSerializationMixins.AttributeMixin;
import com.rorm.serialization.metamodel.ModelSpaceSerializationMixins.CollectionElementTypeMixin;
import com.rorm.serialization.metamodel.ModelSpaceSerializationMixins.DataTypeMixin;
import com.rorm.serialization.metamodel.ModelSpaceSerializationMixins.ReferenceMappingMixin;
import com.rorm.serialization.metamodel.ModelSpaceSerializationMixins.RootMixin;

/**
 * Jackson module that wires the {@link ModelSpaceSerializationMixins} into
 * any {@link com.fasterxml.jackson.databind.ObjectMapper} that auto-discovers
 * modules via SPI. This ensures a {@link com.rorm.metamodel.ModelSpace}
 * round-trips correctly through every Jackson consumer in the stack —
 * Hibernate's {@code JsonType}, Spring's default mapper, Restate's journal
 * serde, Redis value serializers — without each having to register the
 * mixins separately.
 * <p>
 * Auto-discovered via
 * {@code META-INF/services/com.fasterxml.jackson.databind.Module}.
 */
public class ModelSpaceMixinModule extends Module {

    @Override
    public String getModuleName() {
        return "ModelSpaceMixinModule";
    }

    @Override
    public Version version() {
        return Version.unknownVersion();
    }

    @Override
    public void setupModule(SetupContext context) {
        context.setMixInAnnotations(Root.class, RootMixin.class);
        context.setMixInAnnotations(Attribute.class, AttributeMixin.class);
        context.setMixInAnnotations(DataType.class, DataTypeMixin.class);
        context.setMixInAnnotations(ReferenceMapping.class, ReferenceMappingMixin.class);
        context.setMixInAnnotations(CollectionElement.class, CollectionElementTypeMixin.class);
    }
}
