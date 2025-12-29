package com.rorm.query;

/**
 * Represents a subquery expression that can be used in WHERE, SELECT, or other clauses.
 * <p>
 * Examples:
 * <pre>
 * // Scalar subquery in WHERE
 * WHERE total > (SELECT AVG(total) FROM orders)
 *
 * // EXISTS subquery
 * WHERE EXISTS (SELECT 1 FROM orders WHERE customer_id = outer.id)
 *
 * // IN subquery
 * WHERE id IN (SELECT customer_id FROM orders WHERE total > 1000)
 * </pre>
 */
public record Subquery(Query query) implements Expression {
}
