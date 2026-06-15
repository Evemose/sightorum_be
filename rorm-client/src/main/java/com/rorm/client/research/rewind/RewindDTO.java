package com.rorm.client.research.rewind;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rorm.viz.dto.chart.ChartBlock;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Post-run, manager-facing slideshow of a causal analysis.
 *
 * <p>Composed lazily by {@code RewindService}: when the apparatus
 * publishes {@code RunCompleted} on an {@code analysis-*} run, the
 * service kicks off generation in a background virtual thread, fans
 * out a handful of independent Sonnet calls (one per section), and
 * caches the assembled {@link RewindDTO} keyed by runId. The FE polls
 * {@code GET /research/{id}/rewind} and switches its viewport from
 * the anticipation animation to the slide stage once {@code status =
 * READY}.
 *
 * <p>Wire shape mirrors the FE's {@code app/types/rewind.ts} — keep
 * the two in lockstep.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RewindDTO(
    Status status,
    @Nullable String startedAt,
    @Nullable String readyAt,
    @Nullable String error,
    @Nullable ReconSlide recon,
    List<HypothesisChain> hypothesisChains,
    @Nullable VerdictSlides verdict
) {

    public enum Status {
        PENDING,
        IN_PROGRESS,
        READY,
        FAILED
    }

    public enum VerdictTag {
        CONFIRMED,
        PARTIALLY,
        INCONCLUSIVE,
        REFUTED
    }

    /** "What we were trying to figure out" opener. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReconSlide(
        String headline,
        String scope,
        String framing,
        String hopedFor,
        @Nullable String elaborate
    ) {}

    /** One four-slide hypothesis arc. */
    public record HypothesisChain(
        String anchor,
        String hypothesisName,
        InitialProposal initial,
        RefinedProposal refined,
        Verification verification,
        Outcome outcome
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InitialProposal(
        String headline,
        String body,
        Visual visual,
        @Nullable String elaborate
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RefinedProposal(
        String headline,
        String body,
        String whatChanged,
        Visual visual,
        @Nullable String elaborate
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Verification(
        String headline,
        String body,
        List<String> challenges,
        List<String> held,
        @Nullable String elaborate
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Outcome(
        VerdictTag verdict,
        String headline,
        String body,
        @Nullable String caveat,
        @Nullable String elaborate,
        // Opaque chart-block JSON the narrator synthesises from the
        // supervisor / forensic structured outputs (verbatim numbers).
        // Shape matches `app/components/digest/jsonDigestSchema.ts`'s
        // ChartBlock — `{ kind: <ChartKind>, ...props }`. We treat it
        // as passthrough JsonNode so the BE doesn't need to track each
        // chart kind's props; the FE's existing ChartRenderer handles
        // the dispatch.
        @Nullable List<ChartBlock> charts
    ) {}

    public record VerdictSlides(
        BottomLine bottomLine,
        Pattern pattern,
        Caveats caveats
    ) {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record BottomLine(String headline, String body, @Nullable List<ChartBlock> charts) {}
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Pattern(String headline, String body, String crosscut, @Nullable List<ChartBlock> charts) {}
        public record Caveats(String headline, String body) {}
    }

    public enum Visual {
        arrow, cluster, gauge, flow, split
    }
}
