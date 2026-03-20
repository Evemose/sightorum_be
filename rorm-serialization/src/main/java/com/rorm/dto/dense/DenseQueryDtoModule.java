package com.rorm.dto.dense;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;

import java.io.IOException;

/**
 * Jackson module that allows {@link DenseQueryDto} to be deserialized from a
 * JSON string value (stringified JSON) in addition to a normal JSON object.
 * <p>
 * AI tool-calling models sometimes stringify complex nested arguments instead
 * of sending them as proper JSON objects. This module transparently unwraps
 * such strings so that Spring AI's MethodToolCallback can deserialize them.
 * <p>
 * Auto-discovered via {@code META-INF/services/com.fasterxml.jackson.databind.Module}.
 */
public class DenseQueryDtoModule extends Module {

    @Override
    public String getModuleName() {
        return "DenseQueryDtoStringFallback";
    }

    @Override
    public Version version() {
        return Version.unknownVersion();
    }

    @Override
    public void setupModule(SetupContext context) {
        context.addBeanDeserializerModifier(new BeanDeserializerModifier() {
            @Override
            public JsonDeserializer<?> modifyDeserializer(
                DeserializationConfig config,
                BeanDescription beanDesc,
                JsonDeserializer<?> deserializer
            ) {
                if (beanDesc.getBeanClass() == DenseQueryDto.class) {
                    return new StringFallbackDeserializer(deserializer);
                }
                return deserializer;
            }
        });
    }

    private static class StringFallbackDeserializer extends JsonDeserializer<DenseQueryDto> {

        private final JsonDeserializer<?> delegate;

        StringFallbackDeserializer(JsonDeserializer<?> delegate) {
            this.delegate = delegate;
        }

        @Override
        public DenseQueryDto deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                String json = p.getValueAsString();
                try (JsonParser inner = p.getCodec().getFactory().createParser(json)) {
                    inner.nextToken();
                    return (DenseQueryDto) delegate.deserialize(inner, ctxt);
                }
            }
            return (DenseQueryDto) delegate.deserialize(p, ctxt);
        }
    }
}
