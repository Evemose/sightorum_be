package com.rorm.ml;

import com.rorm.engine.QueryTransformer;
import com.rorm.mapper.DenseQueryMapper;
import com.rorm.metamodel.ModelSpace;
import com.rorm.ml.dto.CausalVerificationJobRequest;
import com.rorm.ml.dto.DatasourceConfig;
import com.rorm.ml.dto.PipelineSpecRequest;
import lombok.RequiredArgsConstructor;
import org.jooq.conf.ParamType;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class PipelineSpecConverter {

    private final DenseQueryMapper denseQueryMapper;
    private final QueryTransformer queryTransformer;

    public CausalVerificationJobRequest convert(PipelineSpecRequest spec,
                                                String reason,
                                                ModelSpace modelSpace,
                                                String schema) {
        var query = denseQueryMapper.toEntity(spec.dataQuery(), modelSpace);
        var sql = queryTransformer.transform(query, schema).getSQL(ParamType.INLINED);

        return CausalVerificationJobRequest.builder()
            .reason(reason)
            .hypothesisId(spec.hypothesisId())
            .treatment(spec.treatment())
            .outcome(spec.outcome())
            .treatmentForm(spec.treatmentForm())
            .datasource(new DatasourceConfig(sql, Map.of()))
            .expectedRowCount(spec.expectedRowCount())
            .stripColumns(spec.stripColumns())
            .dagEdges(spec.dagEdges())
            .dsepThreshold(spec.dsepThreshold())
            .adjustmentSet(spec.adjustmentSet())
            .mediatorsExcluded(spec.mediatorsExcluded())
            .positivityCheck(spec.positivityCheck())
            .estimationVariants(spec.estimationVariants())
            .gates(spec.gates())
            .mediation(spec.mediation())
            .grfConfigs(spec.grfConfigs())
            .refutations(spec.refutations())
            .sensitivity(spec.sensitivity())
            .structuralBreaks(spec.structuralBreaks())
            .residualChecks(spec.residualChecks())
            .rangeChecks(spec.rangeChecks())
            .unmeasuredConfounding(spec.unmeasuredConfounding())
            .externalization(spec.externalization())
            .discrepancyLog(spec.discrepancyLog())
            .build();
    }
}
