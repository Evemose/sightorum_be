package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Single anchor candidate proposed by the scout or domain researcher for
 * downstream hypothesis fanout. An anchor is a SEMANTIC frame — a named
 * analytical lens that bundles a set of metamodel entities, not a single
 * entity. The swarm spawns one parallel generator-compiler-sceptic
 * pipeline per accepted anchor after Jaccard-based merge across the two
 * proposers.
 */
@JsonClassDescription("""
    One semantic anchor candidate. An anchor names an analytical frame
    (e.g. 'Equipment capital stock', 'Operational temporal exposure') and
    enumerates the metamodel entities it touches. Both the scout (data
    divergence view) and the domain researcher (literature view) propose
    these; the runtime merges proposals whose entity sets overlap above a
    Jaccard threshold and dispatches one fanout branch per merged anchor.""")
public record ProposedAnchor(

    @JsonPropertyDescription("""
        Semantic name for the anchor frame — a short noun phrase that
        captures the analytical lens (e.g. 'Equipment capital stock',
        'Operational temporal exposure'). Not necessarily an entity name;
        the entity set lives in {@code entities}.""")
    @JsonProperty(required = true)
    String name,

    @JsonPropertyDescription("""
        Metamodel entities this anchor touches. Lower-case identifiers
        matching the schema entities verbatim. One or more — an anchor
        bundles entities under one semantic frame so downstream agents
        scope joins, queries, and DAG construction to this set.""")
    @JsonProperty(required = true)
    List<String> entities,

    @JsonPropertyDescription("""
        Analytical perspective the generator should adopt under this
        anchor. One sentence framing the lens (e.g. 'How does equipment
        condition and aging gate the outcome').""")
    @JsonProperty(required = true)
    String perspective,

    @JsonPropertyDescription("""
        One sentence on why this anchor surfaced: which data divergence,
        domain-known causal frame, or hazard motivates fanning out from
        it.""")
    @JsonProperty(required = true)
    String rationale,

    @JsonPropertyDescription("""
        Provenance label. Set by the runtime after merge; proposers may
        leave it null. Values: 'scout' (only the scout proposed this
        anchor), 'domain' (only the domain researcher proposed it),
        'merged' (both proposed overlapping anchors and the runtime
        concatenated them).""")
    @Nullable String source
) {}
