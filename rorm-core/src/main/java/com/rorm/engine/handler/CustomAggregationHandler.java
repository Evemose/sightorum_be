package com.rorm.engine.handler;

/**
 * Extension point for custom aggregation handlers.
 * <p>
 * Implement this interface to add support for database-specific or
 * application-specific aggregations not covered by the built-in handlers.
 */
public non-sealed interface CustomAggregationHandler extends AggregationHandler {
}
