package com.rorm.client.import_.mapper;

import com.rorm.client.import_.dto.CoercionConfigDTO;
import com.rorm.client.import_.dto.CoercionStrategyDTO;
import com.rorm.dataimport.pipeline.ImportRequest;
import com.rorm.dataimport.type.DbLevelCoercion;
import com.rorm.dataimport.type.InMemoryCoercion;
import com.rorm.dataimport.type.InvalidValueCoercionStrategy;
import com.rorm.dataimport.type.NumericCoercionStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps coercion strategy DTOs to domain objects.
 * Clean, type-safe, no nulls, no parameter hell.
 */
@Component
public class CoercionStrategyMapper {

    /**
     * Convert a list of coercion configs to a map of AttributeKey -> Strategy.
     * No nulls in input or output.
     */
    public Map<ImportRequest.AttributeKey, InvalidValueCoercionStrategy> toCoercionStrategies(
        List<CoercionConfigDTO> configs
    ) {
        return configs.stream()
            .collect(Collectors.toMap(
                config -> new ImportRequest.AttributeKey(config.rootName(), config.attributePath()),
                config -> toDomainStrategy(config.strategy())
            ));
    }

    private InvalidValueCoercionStrategy toDomainStrategy(CoercionStrategyDTO dto) {
        return switch (dto) {
            // In-memory coercions
            case CoercionStrategyDTO.SkipDTO _ -> InMemoryCoercion.Skip.INSTANCE;

            case CoercionStrategyDTO.UseDefaultDTO useDefault ->
                new InMemoryCoercion.UseDefault(useDefault.defaultValue());

            case CoercionStrategyDTO.NullOnInvalidDTO _ -> InMemoryCoercion.NullOnInvalid.INSTANCE;

            case CoercionStrategyDTO.ThrowOnInvalidDTO _ -> InMemoryCoercion.ThrowOnInvalid.INSTANCE;

            case CoercionStrategyDTO.RoundDTO round -> new NumericCoercionStrategy.Round(round.roundingMode());

            case CoercionStrategyDTO.ClampDTO clamp ->
                new NumericCoercionStrategy.Clamp(clamp.minBound(), clamp.maxBound());

            case CoercionStrategyDTO.TruncateDTO _ -> new NumericCoercionStrategy.Truncate();

            // Database-level coercions
            case CoercionStrategyDTO.ForwardFillDTO _ -> DbLevelCoercion.ForwardFill.INSTANCE;

            case CoercionStrategyDTO.BackwardFillDTO _ -> DbLevelCoercion.BackwardFill.INSTANCE;

            case CoercionStrategyDTO.UseMeanDTO _ -> DbLevelCoercion.UseMean.INSTANCE;

            case CoercionStrategyDTO.UseMedianDTO _ -> DbLevelCoercion.UseMedian.INSTANCE;

            case CoercionStrategyDTO.UseModeDTO _ -> DbLevelCoercion.UseMode.INSTANCE;
        };
    }
}
