package com.rorm.ai.tools;

import com.rorm.ai.RormToolContext;
import com.rorm.ai.tools.DescriptiveDigest.FiredCheck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared support classes for descriptive statistics tools.
 * Contains common parameter objects and collectors used across multiple stat tools.
 */
public final class DescriptiveToolSupport {

    private DescriptiveToolSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Collects check results and metrics across multiple evaluation passes.
     * Mutable collector with public fields for easy access.
     */
    public static class CheckCollector {
        public final List<FiredCheck> fired = new ArrayList<>();
        public final List<String> unavailable = new ArrayList<>();
        public final Map<String, Object> rawMetrics = new LinkedHashMap<>();
    }

}
