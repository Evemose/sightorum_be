package com.rorm.query;

/// References a path from an outer query scope in a correlated subquery.
///
/// The depth indicates which outer scope to reference:
///
/// - 1 = immediate parent query
/// - 2 = grandparent query
/// - n = n-th ancestor query
///
/// Example - correlated subquery:
/// ```sql
/// SELECT * FROM customers c
/// WHERE EXISTS (
///     SELECT 1 FROM orders o
///     WHERE o.customer_id = c.id -- c.id is OuterRef(1, path to id)
/// )
/// ```
public record OuterRef(int depth, Path path) implements Expression {

    public OuterRef {
        if (depth < 1) {
            throw new IllegalArgumentException("Outer reference depth must be at least 1, got: " + depth);
        }
    }

    public static OuterRef parent(Path path) {
        return new OuterRef(1, path);
    }
}
