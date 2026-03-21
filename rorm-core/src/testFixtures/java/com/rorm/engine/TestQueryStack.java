package com.rorm.engine;

import com.rorm.fetcher.JooqFetcher;
import com.rorm.testutil.TestHandlerRegistry;
import org.jooq.DSLContext;

/**
 * Creates a fully wired Fetcher + QueryTransformer stack for integration tests
 * outside the rorm-core module (where package-private classes are inaccessible).
 */
public final class TestQueryStack {

    private TestQueryStack() {
    }

    public static JooqFetcher createFetcher(DSLContext dsl) {
        var registry = TestHandlerRegistry.createWithAllBuiltIns();
        var exprTransformer = new ExpressionTransformer(registry);
        var queryTransformer = new QueryTransformer(dsl, exprTransformer, new JoinCollector(exprTransformer));
        return new JooqFetcher(dsl, queryTransformer);
    }
}
