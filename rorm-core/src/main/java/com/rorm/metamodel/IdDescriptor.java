package com.rorm.metamodel;

public record IdDescriptor(BasicAttribute idAttribute) {

    public static IdDescriptor longId(String tableName) {
        return new IdDescriptor(new BasicAttribute(
            "id",
            new AttributeLocation(tableName, "id"),
            new DataType.NumericType(19, 0)
        ));
    }

    public static IdDescriptor stringId(String tableName) {
        return new IdDescriptor(new BasicAttribute(
            "id",
            new AttributeLocation(tableName, "id"),
            new DataType.StringType()
        ));
    }

    public DataType dataType() {
        return idAttribute.dataType();
    }

    public String columnName() {
        return idAttribute.location().column();
    }
}
