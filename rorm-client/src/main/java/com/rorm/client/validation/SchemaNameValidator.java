package com.rorm.client.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class SchemaNameValidator implements ConstraintValidator<ValidSchemaName, String> {

    private static final Pattern SCHEMA_NAME_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private int minLength;
    private int maxLength;

    @Override
    public void initialize(ValidSchemaName annotation) {
        this.minLength = annotation.minLength();
        this.maxLength = annotation.maxLength();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }

        if (value.length() < minLength || value.length() > maxLength) {
            return false;
        }

        return SCHEMA_NAME_PATTERN.matcher(value).matches();
    }
}
