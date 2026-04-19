package com.rorm.query;

/**
 * Describes a set operation (UNION, INTERSECT, EXCEPT) to combine with another query.
 *
 * @param type  the type of set operation
 * @param query the second query to combine with
 */
public record SetOperation(
    SetOperationType type,
    Query query
) {

    public static SetOperation union(Query query) {
        return new SetOperation(SetOperationType.UNION, query);
    }

    public static SetOperation unionAll(Query query) {
        return new SetOperation(SetOperationType.UNION_ALL, query);
    }

    public static SetOperation intersect(Query query) {
        return new SetOperation(SetOperationType.INTERSECT, query);
    }

    public static SetOperation intersectAll(Query query) {
        return new SetOperation(SetOperationType.INTERSECT_ALL, query);
    }

    public static SetOperation except(Query query) {
        return new SetOperation(SetOperationType.EXCEPT, query);
    }

    public static SetOperation exceptAll(Query query) {
        return new SetOperation(SetOperationType.EXCEPT_ALL, query);
    }

    public enum SetOperationType {
        UNION,
        UNION_ALL,
        INTERSECT,
        INTERSECT_ALL,
        EXCEPT,
        EXCEPT_ALL
    }
}
