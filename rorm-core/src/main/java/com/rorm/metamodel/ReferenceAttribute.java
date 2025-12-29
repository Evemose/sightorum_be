package com.rorm.metamodel;

public sealed interface ReferenceAttribute extends Attribute permits SingularReferenceAttribute, PluralReferenceAttribute {

    Root targetRoot();
    ReferenceMapping mappingStrategy();

    sealed interface ReferenceMapping permits InverseRootTableColumn, JoinTableMapping {
    }

    record InverseRootTableColumn(String columnName) implements ReferenceMapping {
    }

    record JoinTableMapping(AttributeLocation joinColumnLocation, String inverseJoinColumnName) implements ReferenceMapping {
    }
}
