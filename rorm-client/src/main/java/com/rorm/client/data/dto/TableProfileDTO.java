package com.rorm.client.data.dto;

import com.rorm.dataimport.pipeline.profile.AttributeStatistics;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Per-table attribute statistics carved out of the pre-computed
 * {@code SchemaProfile} so the FE can render a column-by-column
 * breakdown without firing a live aggregate query.
 *
 * The shape is intentionally close to the analyzer's domain model — the
 * {@code statistics} field is the same polymorphic
 * {@code AttributeStatistics} the analyzer emits, so JSON downstream
 * preserves the discriminator and the FE can split rendering by type
 * (numeric vs categorical vs temporal vs boolean vs reference vs
 * basic) without a translation layer.
 */
public record TableProfileDTO(
    String schemaName,
    String tableName,
    long rowCount,
    Set<String> summaryFlags,
    List<AttributeProfileDTO> attributes
) {
    public record AttributeProfileDTO(
        String name,
        String dataTypeName,
        String category,
        @Nullable AttributeStatistics statistics
    ) {}
}
