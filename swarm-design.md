# Research Swarm Architecture — Formal Design

## Design Philosophy

The architecture maximizes mechanical (deterministic) evaluation and minimizes LLM judgment in evaluation roles. LLMs
are used for what they do well — reasoning, domain knowledge, query translation, causal model specification — while
established ML/causal inference tools handle hypothesis evaluation and evidence assessment.

Key principles:

**LLM affirmative bias is exploited rather than suppressed.** Skeptical agents are prompted to PROVE negatives, aligning
affirmative bias with the skeptical direction. Skeptics must demonstrate material impact through corrected evaluations
with measured deltas — not verbal conclusions.

**Empirical grounding before hypothesis formulation.** Regression-based factor discovery (Phase 2) surfaces which
variables actually predict the outcome. Generators then explain WHY factors matter rather than guessing WHAT matters.

**Correction over judgment.** Skeptics don't declare hypotheses flawed — they re-execute with corrections and the
mechanical delta IS the evidence. A credibility hierarchy (measured correction > partial correction > comment > noise)
replaces binary valid/invalid evaluation.

### Theoretical Foundations

- **Strong Inference** (Platt, 1964): Multiple competing hypotheses with crucial experiments that discriminate between
  them
- **Multiple Working Hypotheses** (Chamberlin, 1890): Prevent "ruling theory" bias through hypothesis diversity
- **Analysis of Competing Hypotheses** (Heuer, 1999): Structured evaluation emphasizing diagnostic evidence
- **Exploratory Data Analysis** (Tukey, 1977): Structured data exploration must precede hypothesis formulation
- **Reference Class Forecasting** (Kahneman & Tversky, 1979; Flyvbjerg, 2006): Ground predictions in domain base rates
  before specific analysis
- **Bayesian Experimental Design** (Lindley, 1956): Value of experiment measured by expected information gain
- **Threats to Validity** (Shadish, Cook & Campbell, 2002): Systematic taxonomy of threats distributed across pipeline
  stages
- **Severe Testing** (Mayo, 1996): Hypothesis passes severe test only if test had high probability of detecting flaw if
  flaw existed
- **Causal Inference** (Pearl, 2000): Backdoor criterion, d-separation, association-intervention distinction
- **Stability Selection** (Meinshausen & Bühlmann, 2010): Subsample-based feature selection with error control
  guarantees
- **Model Class Reliance** (Fisher, Rudin & Dominici, 2019): Feature importance measured across all well-performing
  models
- **Rosenbaum Sensitivity Analysis** (Rosenbaum, 2002): Bounds on strength of unmeasured confounds needed to nullify
  results
- **Hierarchical Causal Models** (Weinstein & Blei, 2024): Extended SCMs with inner plates for nested multi-entity data
- **Threshold Regression** (Hansen, 2000; Bai & Perron, 1998): Detection of structural breaks and nonlinear regime
  changes in relationships
- **Double/Debiased Machine Learning** (Chernozhukov et al., 2018): Semiparametric causal estimation using ML for
  nuisance parameters with cross-fitting for valid inference
- **Generalized Random Forests** (Athey, Tibshirani & Wager, 2019): Heterogeneous treatment effect estimation with valid
  confidence intervals via adaptive kernel weighting
- **E-values** (VanderWeele & Ding, 2017): Minimum confound strength on risk ratio scale needed to explain away observed
  effect
- **Causal Mediation** (Imai, Keele & Tingley, 2010; VanderWeele, 2015): Decomposition of total effects into direct and
  mediated components through identified pathways

---

## Pipeline Overview

```
┌─────────────────────────────────────────────────────────┐
│              PHASE 1: RECONNAISSANCE                     │
│                                                         │
│   Survey Scout ◄──── parallel ────► Domain Researcher   │
│   (data landscape)                  (knowledge landscape)│
│        │                                   │            │
│        └──────────── merged ───────────────┘            │
│                        │                                │
│              Anchor entity selection                     │
│          [User interaction: domain confirmation]         │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│     PHASE 2: HYPOTHESIS GENERATION (per generator)       │
│                                                         │
│   Each generator seeded with anchor entity + attributes  │
│   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │
│   │ Generator A  │  │ Generator B  │  │ Generator C? │   │
│   │ (entity α)   │  │ (entity β)   │  │ (entity γ)   │   │
│   └──────┬──────┘  └──────┬──────┘  └──────┬──────┘   │
│          ↓                ↓                ↓            │
│   Iterative stability analysis loop (per generator):    │
│     Write SQL from anchor → stability selection →       │
│     Interpret: proxy? uncontrollable? nonlinear? →      │
│     Strip / flip target / request SHAP → rerun →        │
│     Repeat until actionable variables surface            │
│          ↓                ↓                ↓            │
│   Output: causal chain + hypothesis specification        │
│                                                         │
│   Cross-entity path discovery (BFS + DP association)     │
│   Mandatory heterogeneity check (residual clustering)    │
│                                                         │
│          ↓                ↓                ↓            │
│   Stage 1 Skeptics challenge strip/flip decisions        │
│   (run counter-stability analyses as evidence)           │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│              PHASE 3: PLAN DERIVATION                     │
│                                                         │
│   Hypothesis merge + dedup (structural identity)         │
│   Discrimination check (structural difference required)  │
│   Execution plan: SQL derivation + preprocessing + model │
│   Threat assignment (Campbell taxonomy, partially LLM)   │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│              PHASE 4: EXECUTION                           │
│                                                         │
│   Per hypothesis, TWO-STAGE architecture:                │
│                                                         │
│   ┌─────────────────────────────────────────────────┐   │
│   │ EXECUTOR COMPILER (LLM, Opus-tier)               │   │
│   │   Hypothesis spec + schema + domain → PipelineSpec│   │
│   │   2-3 exploration rounds, codified failure modes  │   │
│   └──────────────────┬──────────────────────────────┘   │
│                      │ PipelineSpec (typed contract)     │
│   ┌──────────────────▼──────────────────────────────┐   │
│   │ MECHANICAL ENGINE (no LLM)                       │   │
│   │   DML/GRF → refutations → sensitivity →          │   │
│   │   structural breaks → residual checks →           │   │
│   │   E-values/Rosenbaum → externalization → tiers    │   │
│   └──────────────────┬──────────────────────────────┘   │
│                      │                                   │
│   Stage 2 Skeptics challenge PipelineSpec                │
│     Pass 1 (blind): specification concerns               │
│     Pass 2 (informed): re-compile with corrections       │
│     Correction loop: delta > ε → continue                │
│                                                         │
│   Proof paths logged mechanically via tool interception  │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│         PHASE 4.5: HYPOTHESIS COMPOSITION                │
│                                                         │
│   Interaction screening (cross-hypothesis GRF):          │
│     For each surviving hypothesis, run GRF with other    │
│     hypotheses' treatment variables as covariates        │
│     → sparse interaction map (which pairs interact)      │
│                                                         │
│   For interacting pairs:                                 │
│     Multi-treatment DML (joint partial effects)          │
│     GRF interaction surface (functional form:            │
│       additive / amplifying / inhibiting / sign-flip)    │
│                                                         │
│   Joint DAG construction + mediation decomposition       │
│     [TODO: formal DAG merge + mediation framework]       │
│                                                         │
│   Output: causal interaction landscape                   │
│     individual effects + pairwise interaction surfaces   │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│              PHASE 5: EVALUATION                          │
│                                                         │
│   Causal effect assessment (magnitude, CI, significance) │
│   Specification sensitivity (automated grid + timescale) │
│   Quasi-experimental verification:                       │
│     structural breaks → prediction matching              │
│   Causal evidence tier assignment (Tier 1/2/3)           │
│   Robustness: Rosenbaum, E-values, replication, temporal │
│   Unmeasured confounding sensitivity                     │
│   Residual diagnostics → 3-tier confound detection       │
│   Range restriction / reliability reporting              │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│            PHASE 6: INTERPRETATION                        │
│                                                         │
│   Domain Researcher 2nd pass (external validity, neutral)│
│                    ↓                                     │
│   N × (Advocate + Prosecutor) pairs (parallel)           │
│     opposed incentives, same read-only evidence          │
│                    ↓                                     │
│   Judge (reconciles adversarial reports + mech. results) │
│                    ↓                                     │
│   User-facing output (with assumptions blocks)           │
└─────────────────────────────────────────────────────────┘
```

---

## Phase 1: Reconnaissance

### 1.1 Survey Scout

**Role**: Map the data landscape breadth-first.

**Inputs**: Full metamodel (entity-relationship schema)

**Tool access**: `analyzeExpression`, `executeQuery`, `listAttributes`

**Process**: Touches every entity cluster. Produces schema map, not deep analysis.

**Outputs**:

- Per-entity: row count, attribute count, key fields, completeness flags
- Per-entity summary flags: has temporal fields? high null rates? high cardinality categoricals?
- Relationship adjacency list (FK topology — used by Phase 2 cross-entity path enumeration)
- Data quality red flags (entities with >20% nulls, very small tables, etc.)
- **Geospatial anchors**: Fields representing location hierarchy (region → district → zone → site). Identified by
  pattern matching on field names, types, and cardinality ratios. Used by Domain Researcher for targeted search.
- **Measurement metadata annotation**: Per-attribute classification as *measurement of subject* vs *measurement process
  metadata*. Pattern matching on names ("calibration," "install_date," "firmware," "sensor_id," "logger_id") plus
  pairwise LLM classification for ambiguous cases. Used by Phase 5 residual diagnostics to distinguish instrumentation
  artifacts from real effects.

**Information tiering**:

- **Tier 1** (always in context, ~200 tokens): Entity names + descriptions + row counts + relationship graph + summary
  flags. Every downstream agent sees this.
- **Tier 2** (tool call, per-entity): Attribute list with types for a specific entity. Agents browse on demand via
  `listAttributes`.
- **Tier 3** (tool call, per-attribute): Full distribution stats. Agents query via `analyzeExpression` only for
  attributes they care about.

**Design rationale**: Lazy loading prevents context pollution. For wide schemas (100+ entities), pushing all attribute
stats into context wastes tokens on mostly irrelevant information. Generators pull Tier 2/3 data guided by their
perspective.

**Pre-computation**: Tier 3 stats can be computed at data import time since data is static. Stored in lookup table,
exposed via existing tools.

### 1.2 Domain Researcher

**Role**: Map the knowledge landscape for the data domain.

**Runs**: Parallel with Survey Scout.

**Inputs**: Full metamodel, user query

**Tool access**: Web search, data query tools (for domain inference from opaque schemas)

**Process**:

1. Infer domain from schema structure, entity names, sample values. For opaque schemas, may query a few categorical
   columns to identify domain.
2. Research domain commonalities: typical metric ranges, known drivers, common effect sizes, industry benchmarks.
3. Identify expected patterns: what relationships are well-established in this domain?

**Outputs**: Domain context document containing:

- Domain identification and confidence
- Reference class baselines (e.g., "typical SaaS churn 5-7% annually")
- Known causal drivers in this domain
- Typical effect sizes (e.g., "single-variable interventions typically move churn 1-3pp")
- Common pitfalls and confounds specific to this domain
- Industry-specific metric definitions

**Theoretical basis**: Reference class forecasting (Kahneman). Establishing base rates from the relevant reference class
before making specific predictions produces dramatically better-calibrated estimates than inside-view analysis alone (
Flyvbjerg, 2006).

### 1.3 Anchor Entity Selection

**Inputs**: Survey Scout schema map, Domain Researcher context, user query

**Process**: LLM call identifies 3-5 non-target entities whose FK paths to the target entity represent distinct causal
perspectives.

**Key constraint**: The target entity (e.g., `shipments.excursion_flag`) must NOT be an anchor. Anchoring on the fact
table produces kitchen-sink queries with no causal structure — every column is one join away, so nothing constrains the
feature set.

**Output**: Per-generator anchor assignment: an entity + seed attributes. The anchor constrains which FK paths are
traversed, which determines the feature set, which IS the causal commitment.

**Example** (pharmaceutical cold chain, target = `shipments.excursion_flag`):

- Generator A anchor: `node_ops_logs` attributes `internal_temp_c`, `refrig_health_pct` → "before the trip" causal root
- Generator B anchor: `shipments` route attributes `route_total_stops`, `route_total_drive_hours` → "during the trip"
  causal root
- Generator C anchor: `receiving_logs` attributes `receiving_delay_min`, `has_temp_dock` → "after the trip" causal root

Different anchors force different join topologies → different feature sets → different implicit causal models. This is
not strategic coverage — it's what genuinely independent domain experts would naturally focus on.

---

## Phase 2: Hypothesis Generation (Iterative Stability Analysis)

Generator = iterative stability analysis loop. The hypothesis emerges from the sequence of stability runs, not from an
LLM proposing causal edges after a one-shot regression.

**Theoretical basis**: Tukey's Exploratory Data Analysis (1977) — structured data exploration must precede hypothesis
formulation. Stability selection (Meinshausen & Bühlmann, 2010) — subsample-based feature selection with error control
guarantees. Model class reliance (Fisher, Rudin & Dominici, 2019) — importance measured across all well-performing
models, not one.

**Key insight from case study validation**: The original design separated factor discovery (Phase 2) from hypothesis
generation (Phase 3). In practice, interpreting stability results IS causal reasoning — "this is a mediator," "this is
uncontrollable," "this has a threshold" are causal claims. The strip/flip decisions ARE the hypothesis. There is no
separate generation step.

### 2.1 Generator Loop

**Count**: 2-3 generators, each seeded with a different anchor entity from Phase 1.3.

**Each generator receives**:

- Its assigned anchor entity + seed attributes
- Tier 1 schema map + FK topology
- Domain Researcher context
- User query (prescriptive: "how do I reduce X?")
- Tool access: stability selection service, SHAP curves, data query tools

**Process** (iterative, per generator):

1. **Initial SQL construction**: Build query from anchor entity through FK paths to the target. Include anchor
   attributes + attributes from joined entities along the path. Verify output row count matches target entity grain (
   guard against 1:N join fan-out — aggregate N-side tables before joining).

2. **Stability selection**: Run across 4 model families (linear, elastic net, LGBM, random forest) × 50 bootstrap
   samples. Produces: per-feature selection frequency, rank stability, cross-model agreement, correlated feature groups,
   nonlinear/interaction candidates (tree rank >> linear rank).

3. **Interpret dominant feature**:
    - **Near-outcome proxy** (dominates all models, importance >> 2nd feature, highly correlated with target): flag as
      mediator. Flip target — make this feature the new outcome, rerun with its upstream causes. Example:
      `pre_departure_temp_c` screens everything → becomes new target.
    - **Uncontrollable dominant** (no plausible intervention exists): strip from feature set, rerun. The prescriptive
      question demands actionable causes. Example: `ambient_temp_at_dispatch_c` stripped — can't control weather.
    - **Nonlinear candidate** (tree rank >> linear rank): request SHAP dependence curve + Muggeo segmented regression
      for breakpoint detection. Report threshold value and confidence. Example: `container_age_months` tree rank 1,
      linear rank 17 → SHAP reveals 30-month threshold.
    - **Correlated group**: flag for disambiguation — "one of these matters, determine which before assigning credit."
    - **Actionable variable surfaces**: hypothesis crystallizes. Record the causal chain from target through each layer
      of decomposition.

4. **Repeat** until actionable variables surface or the generator exhausts its anchor's explanatory scope (some anchors
   reach a dead end — node operations can't explain transit effects).

**Output per generator**:

- The chain of stability runs + interpretations = the causal hypothesis
- Natural language description of the causal mechanism
- Hypothesis specification (treatment, outcome, graph edges, preprocessing)
- Declared confound variables
- Evidence tier expectation (does the data contain quasi-experimental variation for this hypothesis?)

**Diversity mechanism**: Different anchor entities naturally produce different iterative paths. `node_ops_logs` anchor →
discovers departure temp → strips ambient → finds refrig_health, container_age. `receiving_logs` anchor → discovers
receiving_delay → strips after-hours → finds dock infrastructure. These are genuinely different causal hypotheses from
different starting points.

### 2.2 Stability Selection Mechanics

Each stability run within the generator loop:

1. **Multiple model training**: Fit target against feature set using linear regression, elastic net, LGBM, and random
   forest (or ExtraTrees for speed). Each captures different relationship structures.

2. **Stability selection**: For each model type, bootstrap 50 subsamples (80% of data each). Train on each, extract
   feature importances. Compute per-feature selection frequency and rank stability.

3. **Model class reliance**: Compare feature rankings across model types. Features ranked consistently high across all
   types are robustly important. Features high in tree models but low in linear suggest nonlinear effects.

4. **Correlated feature groups**: Identify features with >0.8 pairwise correlation. Report as groups.

5. **Nonlinear/interaction detection**: Flag features where tree mean rank >> linear mean rank (gap > 3 rank positions).
   These are candidates for SHAP dependence analysis.

**Output per run**:

```
Robust features:    [pre_departure_temp_c (sel_freq=1.0, rank 1 all models)]
Nonlinear candidates: [container_age_months (tree=3, linear=17)]
Correlated groups:  [{climate_zone, region} (r=0.92)]
```

### 2.3 SHAP Dependence and Breakpoint Detection

When a nonlinear candidate is identified, the generator requests SHAP analysis:

1. Compute SHAP values across all 50 bootstrap LGBM models from the stability run
2. Bin feature values into 100-point grid, average SHAP contributions per bin across models
3. Fit Muggeo segmented regression to the mean SHAP curve
4. Report: breakpoint median ± IQR across 50 models, convergence count

A breakpoint is robust when: convergence > 40/50, IQR < 20% of feature range. The breakpoint value feeds into the
hypothesis specification as a threshold transformation for downstream causal tools.

**Literature**: Hansen (2000) threshold regression, Bai & Perron (1998) structural breaks, Lundberg & Lee (2017) SHAP.

### 2.4 Cross-Entity Path Discovery

Unchanged from previous design. Mechanical BFS/DFS on Scout's FK topology enumerates cross-entity paths. DP-constrained
attribute association screening filters viable paths. Multi-level aggregation sensitivity catches ecological fallacy.
Generators receive empirically-supported paths as a fixed menu.

**Literature**: Weinstein & Blei (2024), Sgouritsa et al. (2024).

### 2.5 Mandatory Outcome Heterogeneity Check

Before any causal modeling, cluster residuals from initial stability LGBM to detect subpopulations. SHAP interaction
values surface candidate moderators. If distinct clusters exist → flag for heterogeneous treatment effect estimation via
GRF in Phase 4 (replaces the previous approach of splitting population and running separate causal models per cluster).

### 2.6 Stage 1 Skeptic: Challenge Generation

After each generator completes its iterative loop, a skeptic challenges the strip/flip decisions by running *
*counter-stability analyses** with corrected feature sets.

**Mechanism**: The skeptic receives the generator's full chain of stability runs and decisions. For each strip/flip:

- Generator stripped variable X as uncontrollable → Skeptic re-runs with X decomposed or interacted with a controllable
  variable
- Generator chose variable A from correlated group {A, B, C} → Skeptic re-runs with B or C instead
- Generator flipped target to mediator M → Skeptic re-runs conditioning on M instead of targeting it

The intervention is empirical: the skeptic runs analyses that either confirm or refute the generator's decision. The
delta in downstream results IS the evidence.

**What this catches**:

- Wrong variable dropped from a correlated group (generator picks proxy, real cause is another member)
- Premature stripping of a variable with a controllable sub-component
- Target flip that skipped a causal level

**What this doesn't catch**:

- Variables never included because the anchor entity didn't traverse them (that's cross-generator diversity)
- Epistemological limits (PCS-8,9,10)

**Correction loop**: If skeptic's counter-analysis produces materially different results (delta > ε), the generator
revises its hypothesis. If the generator's original decision holds, the skeptic's challenge is recorded but doesn't
alter the hypothesis.

### 2.7 Data Engineering Guards

Generators must verify data integrity before interpreting stability results:

**1:N join fan-out**: After any JOIN, verify output row count matches target entity grain. If row count increased, the
N-side table has multiple rows per join key. Aggregate to correct grain before joining. Example:
`shipments JOIN node_ops_logs` inflated 562k to 720k rows because ops logs multiple times per node per day — daily
average required.

**Intrinsic column exclusion**: Agent-visible data must not contain simulation internals or god's-eye fields. Any field
exposing measurement process metadata (actual vs observed temps, fault labels, bias values) that a real-world analyst
wouldn't have access to must be excluded from the dataset before analysis.

---

## Phase 3: Plan Derivation

Mostly mechanical. Threat assignment involves partial LLM reasoning; other steps are deterministic.

### 3.1 Hypothesis Merge and Deduplication

Collect all hypothesis specifications from generators. Compare structural inputs (treatment, outcome, graph edges,
preprocessing). Merge structurally identical hypotheses.

### 3.2 Discrimination Check

Hypotheses must differ structurally — different graph, different treatment, or different preprocessing. If all
generators produced structurally identical hypotheses, the hypothesis set has no tension. Flag for generator revision.

### 3.3 DAG Refinement

Three-step process: broad LLM proposal → semantic LLM challenge → mechanical falsification.

**Design principle**: Generators should propose DAGs that are deliberately BROAD — include every plausible edge and
mediator. Over-inclusion is safer than under-inclusion: a spurious extra edge means controlling for something
unnecessary (reduces precision but doesn't bias). A missing edge means uncontrolled confounding (biases the estimate).
The mechanical step prunes what the data doesn't support.

**Step 1 — Generator proposes broad DAG** (already completed in Phase 2): The DAG from the hypothesis specification.
Generator's anchor bias means it may have missed paths outside its entity neighborhood, but Stage 1 skeptic in Phase 2.6
challenged for missing nodes.

**Step 2 — Skeptic semantic challenge on DAG structure**: The skeptic's most valuable contribution at this stage is
proposing missing NODES (mediators, confounders the generator's anchor didn't traverse), not debating individual edges.
Edge validity is mechanically testable in Step 3; node existence is not. Skeptic prompt: "Propose specific missing nodes
or edges with mechanistic justification. For each proposed node, identify where it sits in the entity schema."

**Step 3 — Mechanical d-separation refinement** (no LLM): For every pair of non-adjacent nodes in the DAG, the graph
implies conditional independence given some conditioning set. Test each implication against data:

1. Enumerate all non-adjacent node pairs in the DAG
2. For each pair (A, B), use d-separation algorithm to find conditioning sets S where the DAG claims A ⊥ B | S
3. Residualize A and B on S, test residual correlation
4. If significant correlation (p < 0.05) where DAG claims independence → **missing edge**. Add A→B or B→A (direction
   determined by temporal ordering if available, otherwise flagged as ambiguous)
5. Re-run DoWhy identification on the refined DAG

This is equivalent to using the PC algorithm as a DAG validation tool, not a discovery tool. The DAG comes from the
generator + skeptic; d-separation testing falsifies specific independence claims.

**Significance thresholding**: With large datasets, any non-zero correlation is statistically significant (p < 0.05).
The practical threshold for violation uses adaptive magnitude scaling: `abs(r) > max_abs_r ** 1.5` where `max_abs_r` is
the strongest partial correlation observed across all implications. This calibrates to the dataset's signal regime —
weak-signal datasets get lenient thresholds, strong-signal datasets get stricter ones. The p-value threshold (0.05)
remains as a guard against small-subsample false positives.

**Two-pass resolution**: When violations are found:

1. **Structural pass**: Agent examines violations for common-cause patterns (multiple variables correlated with one
   underconnected node → missing common cause). Proposes new node from schema, adds causal edges, re-runs d-sep.
2. **Fallback pass**: Remaining violations after structural pass get direct edges added between the violating pair.
   These are **flagged as fallback edges** — statistically necessary but mechanistically unexplained.

**Computational cost**: O(P²) partial correlation tests where P is the number of nodes in the DAG. For typical
hypothesis DAGs (5-15 nodes), this is tens to low hundreds of tests — trivial.

**Output**: Refined DAG with:

- Original edges (from generator)
- Structural edges (from agent-proposed common cause nodes, with rationale)
- **Fallback edges** (from unresolved d-sep violations, flagged):
  ```
  {edge: (A, B), type: "fallback", r: 0.12, p: 1e-200,
   attempted_resolution: "vehicle_generation as common cause — partially resolved",
   investigation_needed: true}
  ```
- Edge direction confidence (temporal ordering available vs ambiguous)
- List of tested implications and results (for proof path)

Fallback edges are passed to Phase 4 Stage 2 skeptics as priority investigation targets.

### 3.4 Execution Plan

For each hypothesis, derive the execution pipeline:

1. **Feature derivation queries** — SQL to compute treatment/outcome/confound variables from raw schema
2. **Preprocessing steps** — clustering, dimensionality reduction, feature engineering
3. **Estimation strategy inputs** — refined DAG + variable derivations feed identification scanner and estimation
   stack (DML/RDD/IV/DiD/GRF wrapped in DoWhy)
4. **Shared computation deduplication** — hypotheses sharing treatment or outcome variables share derivation queries

### 3.5 Skeptical Threat Assignment

Per hypothesis, assign threat directions for specification skeptics based on:

| Hypothesis characteristic                         | Applicable threat                                                                       |
|---------------------------------------------------|-----------------------------------------------------------------------------------------|
| Graph assumes X→Y without temporal evidence       | Reverse causation                                                                       |
| Confound set may be incomplete                    | Unmeasured confounding                                                                  |
| Preprocessing clusters by single dimension        | Hidden subgroup effects (Simpson's)                                                     |
| Control variables may be treatment-affected       | Contaminated counterfactual                                                             |
| Data has known completeness issues (Scout)        | Survivorship / attrition                                                                |
| Treatment derivation involves aggregation choices | Operationalization sensitivity                                                          |
| **DAG contains fallback edges from Phase 3.3**    | **Unexplained association — investigate mechanism, direction, or missing common cause** |

Output: per hypothesis, a set of threat assignments. Each becomes one skeptic's mandate in Phase 4.

---

## Phase 4: Execution

Phase 4 uses a **two-stage architecture**: an LLM-based Executor Compiler produces a typed PipelineSpec, which a
mechanical engine executes deterministically. The Compiler makes all judgment calls (DAG construction, confounder
selection, threshold calibration); the Engine makes none.

### 4.0 Architectural Split: Compiler + Engine

**Why two stages?** The original Executor was a single LLM agent that both designed and ran the causal pipeline. This
created two problems: (1) the LLM made ad hoc decisions during execution that were hard to audit, and (2) long-running
ML jobs required the LLM to stay live, which current frameworks handle poorly.

The split resolves both. The Compiler produces a complete, typed specification (PipelineSpec) that captures every
decision. The Engine is a deterministic Python/notebook runner that consumes the spec. The Compiler's decisions are
auditable via the spec; the Engine's execution is reproducible via the spec.

**Compiler** (LLM, Opus-tier): Receives hypothesis specification + schema + domain context. Has tool access to
`executeQuery`, `analyzeExpression`, `engineerDerivedFeature`. Uses 2-3 exploration rounds to parameterize the pipeline.
Produces PipelineSpec.

**Engine** (no LLM): Reads PipelineSpec JSON. Executes query, runs DML/GRF estimation, refutations, sensitivity,
structural breaks, residual checks, range checks, unmeasured confounding analysis, externalization tests, and tier
assignment. All mechanically, with no further LLM involvement.

### 4.1 Execution Pipeline

Per hypothesis, the Compiler translates the hypothesis spec into a PipelineSpec, then the Engine executes:

```
Hypothesis Spec + Schema + Domain Context
  → COMPILER (LLM):
      Data exploration (2-3 rounds: join integrity, d-sep calibration, VIF)
      → Query DTO construction + row count verification
      → DAG construction (greedy W, precise edges)
      → Failure mode checks (bundled vars, positivity, collinearity, mediators,
          temporal confounding, proxy absorption, colliders, table-grain)
      → Gate threshold calibration (formulas, not defaults)
      → Estimation variant design (with executable filter DTOs for scoped variants)
      → GRF/sensitivity/structural break configuration
      → Unmeasured confounding configuration (E-value + Rosenbaum per variant)
      → OUTPUT: PipelineSpec (typed JSON contract)
  → ENGINE (no LLM):
      Query execution → DML estimation → GRF heterogeneity →
      Refutations → Sensitivity grid → Structural breaks →
      Residual diagnostics → Range checks → E-values/Rosenbaum →
      Externalization tests → Tier assignment
      → OUTPUT: Full causal evidence package
```

#### Identification Strategy Scanner

Before committing to an estimation method, the Compiler scans the hypothesis specification and data structure for
quasi-experimental opportunities. Mechanical pattern matching, no LLM:

- **Threshold detector → RDD**: Treatment variable has a threshold assignment rule (e.g., Phase 2.3 breakpoint detection
  found a structural break in a policy/assignment variable). If running variable and cutoff exist → Regression
  Discontinuity Design. Validate with density test (McCrary) at cutoff.
- **Instrument scanner → IV**: Variables in the causal graph that affect treatment but have no plausible direct path to
  outcome (exclusion restriction candidate). Phase 2.4 cross-entity path discovery provides the candidate set —
  variables with strong association to treatment entity but weak/zero association to outcome entity when conditioned on
  treatment. Flag for generator confirmation of exclusion restriction.
- **Time/panel detector → DiD / ITS / SDiD**: Treatment varies across entities and time (staggered adoption, policy
  rollout). If panel structure exists with identifiable pre/post periods → Difference-in-Differences or Synthetic DiD.
  If single treated unit with time series → CausalImpact (Bayesian structural time series for counterfactual
  projection).
- **Default → DML**: When no stronger quasi-experimental structure is detected, Double Machine Learning with the Phase 2
  model ensemble as first-stage nuisance estimators.

The scanner may identify multiple applicable strategies. All viable strategies run in parallel.

**Literature**: Imbens & Lemieux (2008) on RDD, Abadie et al. (2010) on synthetic control, Arkhangelsky et al. (2021) on
synthetic DiD.

#### Parallel Estimation

Three estimation tracks run for each hypothesis where applicable:

**Primary strategy** (from scanner): The strongest quasi-experimental design the data supports — RDD, IV, DiD/SDiD, or
CausalImpact. When available, this provides the highest-credibility estimate because it exploits a specific
identification opportunity.

**DML universal fallback** (always runs): Double Machine Learning (Chernozhukov et al. 2018) provides a semiparametric
estimate that handles high-dimensional confounding. Key architectural advantage: DML's first stage (nuisance parameter
estimation) directly reuses the Phase 2 LGBM/ensemble models. Those models were built for factor discovery; now they
serve double duty as flexible nuisance function estimators for the treatment and outcome equations. Cross-fitting (
sample splitting) ensures valid inference despite ML first stage. DML always runs regardless of whether a primary
strategy exists — it provides a universal comparison point across all hypotheses.

**DML treatment form selection**: The `model_final` parameter encodes the assumed shape of the treatment-effect
function. This is a meaningful decision driven by Phase 2.5 nonlinearity scan results:

- **No breakpoint detected → `LinearDML`**: Constant marginal effect. One coefficient per treatment variable.
- **Breakpoint detected → dual run**:
    1. `LinearDML` with continuous treatment — average marginal slope across the full range
    2. `LinearDML` with binary threshold treatment at the breakpoint — the jump effect at the threshold (the actionable
       number: "what happens when you cross X months")

  Both run as primary estimates, not sensitivity variants. The continuous estimate is the average marginal effect; the
  binary estimate is the intervention-relevant effect. When they diverge substantially, the treatment-outcome
  relationship is nonlinear and the binary threshold is more prescriptively useful.

- **Multiple breakpoints / complex shape → `NonParamDML`**: Arbitrary `model_final` (tree-based or spline). Used when
  the treatment-effect function has multiple regimes that a single threshold doesn't capture.

The breakpoint value from Phase 2.5 (Muggeo segmented regression on SHAP dependence curve) feeds directly to the
executor as the threshold cutoff. This is mechanical — no LLM judgment needed for which DML variant to run.

**GRF for heterogeneity** (conditional): When Phase 2.5 mandatory heterogeneity check flagged subpopulations,
Generalized Random Forests (Athey, Tibshirani & Wager 2019) estimate conditional average treatment effects as a function
of covariates. This replaces the previous approach of "cluster then estimate separately" — GRF produces continuous
heterogeneity estimates without requiring discrete subgroup boundaries, and provides valid confidence intervals for
individual-level treatment effects.

DoWhy serves as the identification and refutation framework wrapping these estimators. It provides the DAG-based
identification check (backdoor criterion, frontdoor, IV validity), refutation tests (placebo treatment, random common
cause, data subset), and sensitivity analysis infrastructure. The estimators above plug into DoWhy as estimation
backends.

#### Conditional Mediation Decomposition

Triggered only when the hypothesis specification includes `independently_actionable_mediators` — variables where
intervention on the mediator is possible without intervening on the treatment (e.g., re-insulate a container without
replacing it). The generator populates this field based on domain reasoning: "can you intervene on this variable
independently?"

If the list is empty, no mediation analysis runs. If populated:

1. **Total effect** — already estimated above (mediator NOT in confounder set)
2. **Direct effect** — one additional EconML DML run per mediator, with the mediator added to the confounder set. Uses
   the same refined DAG for identification context but calls EconML directly rather than modifying the DAG (adding
   `treatment → mediator` edges risks creating cycles with fallback edges). DoWhy's refutation suite does not run on
   direct effect estimates.
3. **Mediated effect** — arithmetic: total − direct. No additional model run.

**Output**:

```
mediation_decomposition:
  mediator: ins_type_enc
  total_effect: 0.0215 pp/month
  direct_effect: 0.0180 pp/month
  mediated_effect: 0.0035 pp/month (16%)
  status: INFORMATIONAL
  assumption: "No unmeasured mediator-outcome confounding.
               Refutations cover total effect only.
               Direct effect estimate not independently verified."
```

**Design rationale**: Mediation answers "what mechanism to intervene on" (re-insulate vs replace). GRF answers "which
subgroup to prioritize" (XPS first vs VIP last). These are complementary prescriptive questions, but mediation requires
an additional unverifiable assumption (no mediator-outcome confounding) that the total effect does not. Mediation is
therefore conditional and informational, not default and verified. GRF heterogeneity analysis is the primary mechanism
decomposition tool.

### 4.1b PipelineSpec Schema

The PipelineSpec is the typed contract between Compiler and Engine. Every Compiler decision is captured as a field
value. A Haiku-class extraction model converts the Compiler's structured natural-language output into this schema.

```
PipelineSpec:
  hypothesis_id: string
  treatment: string                    — column name
  outcome: string                      — column name
  treatment_form: enum                 — CONTINUOUS | BINARY_THRESHOLD | CATEGORICAL

  query: DenseQueryDTO                 — actual query DTO (same DSL as executeQuery)
  expected_row_count: int              — verified by compiler's count query
  strip_columns: [string]              — in query but excluded from analysis

  dag_edges: string                    — digraph notation
  dsep_threshold: double               — correlation magnitude threshold

  adjustment_set: [string]             — W matrix columns
  mediators_excluded: [{ column, pathway, direct_effect_variant_id }]

  estimation_variants: [
    { id, treatment_column, treatment_form, model_type, w_columns,
      reference_category?, threshold_value?,
      filter?: { column, operator: IN|GT|LT|EQ, values },
      notes }
  ]

  gates:
    nuisance_r2: { outcome_abort, outcome_flag, treatment_abort,
                   treatment_flag, treatment_structural_max_r2 }
    sanity: { expected_direction: +1|-1, abort_magnitude, flag_magnitude }
    placebo: { flag_ratio }

  mediation: [{ mediator, pathway, total_variant_id, direct_variant_id }] | null
  grf_configs: [{ id, modifier_columns, slicing }]
  refutations: [{ type: PLACEBO|RANDOM_CAUSE|SUBSET|TEMPORAL_PLACEBO }]

  sensitivity:
    confounder_drops: [{ column, deviation_threshold_pct }]
    confounder_adds: [{ column, reasoning }]
    threshold_variants: [{ threshold, expected_n_treated, expected_n_control }]
    model_variants: [{ primary_variant_id, alternative_model_type }]

  structural_breaks: [{ id, entity_column, temporal_column, temporal_grain,
    pelt_penalty, min_obs_per_period, known_events_tables,
    entity_count, temporal_points }]

  residual_checks:
    autocorrelation: [{ temporal_column, grain, lags, threshold }]
    field_correlation: { threshold, check_columns }
    auto_correction: { max_iterations, stop_criterion_ci_pct }
    metadata_correlation: [{ column, threshold, alert_type }]

  range_checks:
    vif: { threshold, drop_pairs }
    overlap: [{ variant_id, threshold, response_strategy, trim_bounds? }]
    variance: [{ column, structural_note }]

  unmeasured_confounding: [{ variant_id, method: E_VALUE|ROSENBAUM_BOUNDS,
    null_hypothesis, notes }]

  externalization:
    domain_rankings: [{ domain_ranking, source, comparison_method,
                        scope, expected_concordance }]
    allocation_bias: [{ treatment_column, grouping_column, flag_threshold }]

  discrepancy_log: [{ field, generator_value, compiler_value, resolution }]
```

### 4.1c Compiler Codified Failure Modes

The Compiler prompt encodes accumulated lessons from iterative validation. Key failure modes with formulas:

- **Post-treatment variables**: "Could treatment have caused this value?" → exclude from W
- **Positivity violations**: Treated <1% → specify TRIM/MATCH/LATE strategy
- **Treatment near-degeneracy**: Structural max R²: categorical = 1-(1/k), binary = 4×p×(1-p). Abort at <5% of max.
- **Collinear confounders**: VIF > 50 → drop one, document which and why
- **Mediators in W**: Blocks indirect effect → exclude, add direct-effect variant
- **Temporal confounding**: Multi-grain inclusion at every scale where drift is plausible (year for secular trends,
  month for seasonality, finer if system operates at that scale)
- **Bundled variables**: Within-treatment-level variance ≈ 0 → exclude (same manufacturing decision)
- **Proxy absorption**: Finer variable subsumes coarser; ecological fallacy dimension mandatory in W
- **Collider conditioning**: Common effect of treatment and outcome ancestors → exclude
- **Table-grain confusion**: Entity-constant variables absorbed by entity ID → document

**Explicit formulas** (prevent parameter drift):

- PELT penalty: sensitive = log(T), conservative = 3×log(T)
- D-sep threshold: 1.5-2× max noise from genuinely independent pairs (unrelated measurement domains)
- Outcome R² calibration: max corr² with outcome as achievable lower bound; abort well below, flag at ~1/3

#### Sensitivity Suite

Method-specific diagnostics run alongside universal checks:

- **RDD**: McCrary density test at cutoff, bandwidth sensitivity, placebo cutoffs
- **DiD/SDiD**: Pre-trend parallel trends test, event study plot, placebo treatment periods
- **IV**: First-stage F-statistic (weak instrument test), overidentification test if multiple instruments
- **DML**: Cross-fit stability, Neyman orthogonality diagnostic
- **CausalImpact**: posterior predictive checks, counterfactual projection stability
- **Universal**: E-values (VanderWeele & Ding 2017) alongside Rosenbaum bounds, placebo treatment, random common cause,
  data subset stability

**Output per hypothesis**:

```
primary_strategy: DiD (staggered adoption detected)
primary_estimate: +4.2pp (CI: [2.1, 6.3])
  pre_trend_test: pass (p=0.71)
  placebo_periods: pass

dml_estimate: +3.8pp (CI: [1.9, 5.7])
  cross_fit_stable: yes (variance across folds: 0.3pp)

grf_heterogeneity: flagged
  subgroup_cate_range: [+1.1pp, +7.4pp]
  strongest_moderator: sku_stability_band

identification_framework: DoWhy
  backdoor_criterion: satisfied
  refutations:
    placebo_treatment: pass (p=0.82)
    random_common_cause: pass
    data_subset: pass

sensitivity:
  e_value: 2.8 (unmeasured confound must be 2.8× associated with both treatment and outcome to nullify)
  rosenbaum_gamma: 1.9

proof_path: [all SQL queries, preprocessing params, model inputs, scanner decisions]
```

### 4.2 Stage 2 Skeptic: Challenge PipelineSpec

Stage 2 skeptics now challenge the **PipelineSpec**, not ad hoc executor decisions. The spec is the complete auditable
artifact. This is Stage 2 — challenging the Compiler's causal model specification. Stage 1 (challenging the generator's
strip/flip decisions) occurs in Phase 2.6.

#### Skeptic Two-Pass Structure

**Pass 1 — Blind threat assessment** (before execution):

- Receives: hypothesis specification + schema + domain context + **fallback edge flags from Phase 3.3**
- Produces: natural language concerns about the specification
- **For each fallback edge**: skeptic receives the edge, its partial correlation, the attempted structural resolution,
  and must propose a mechanistic explanation. Example: "reefer_kw → container_age (r=0.12, fallback). vehicle_generation
  partially resolved. Remaining association likely from retirement-sorts-by-efficiency: within a generation, higher-kW
  vehicles survive longer, so observable old vehicles are disproportionately high-kW. Check fleet_transitions: do
  retired vehicles have lower reefer_kw than survivors of the same generation?"
- No tool calls — pure reasoning about vulnerabilities
- Cheap, parallel across all skeptics

**Pass 2 — Informed specification challenge** (after engine execution, receives full engine output):

- Proposes concrete PipelineSpec corrections:
    - Different DAG edges or W matrix composition
    - Different gate thresholds or estimation variants
    - Missing failure mode checks (bundled variables, positivity, temporal confounding)
    - Different data composition (exclude survivorship-biased subset)
    - **Fallback edge resolution**: for flagged edges, test the proposed mechanism, then either: (a) confirm the edge
      direction and mechanism, reclassifying from "fallback" to "explained"; (b) reverse the edge direction; or (c)
      propose a new common cause node
- The Compiler re-compiles PipelineSpec with corrections → Engine re-runs
- Delta: difference in causal effect estimate between original and corrected specs

#### Skeptic Output Structure

Ranked by credibility:

1. **Re-run with corrected specification, measured delta in causal effect** — strong evidence. "Removing contaminated
   control variable changes effect from +4.2pp to +1.1pp."
2. **Partial correction with explicit assumptions** — moderate evidence. "Assuming 30% attrition, re-weighting shifts
   effect to +2.8pp."
3. **Comment referencing model specification** — weak signal. "Data only contains active customers; survivorship bias
   likely but uncorrectable."
4. **Ungrounded comment** — noise, discarded.

#### Correction-Reexecution Loop

1. All skeptic corrections collected after initial pass
2. Corrections merged, causal model re-run with corrected specifications
3. Delta: change in causal effect estimate (magnitude, direction, confidence)
4. **Delta > ε and positive**: accept corrections, re-run skeptics on corrected version
5. **Delta ≤ 0**: correction degraded results, discard, terminate
6. **0 < Delta < ε**: accept, terminate (diminishing returns)

Deterministic termination based on mechanical delta computation.

### 4.3 Early Termination

If causal model returns no significant effect (posterior probability below threshold, or confidence interval spanning
zero), terminate skeptical checks for that hypothesis. No value in challenging the specification of a model that found
nothing.

---

## Phase 4.5: Hypothesis Composition

### Problem Addressed

Individual hypotheses are estimated in isolation. Each generator's entity anchoring deliberately produces
non-overlapping feature sets — this is good for hypothesis diversity but means no single hypothesis ever sees the full
causal landscape. Cross-hypothesis interactions (where the effect of one cause depends on the level of another cause
from a different hypothesis) are structurally invisible to individual estimation. The judge's verbal synthesis ("both
matter") cannot quantify whether causes are additive, amplifying, inhibiting, or sign-flipping.

### Prerequisites

Phase 4.5 runs only after individual hypothesis estimation (Phase 4.1-4.3) completes. Only hypotheses that survived
early termination (significant effect, CI not spanning zero) and skeptic challenges (specification corrections
integrated, estimate still significant) enter composition. This is critical — you're composing validated components, not
speculative ones.

### 4.5.1 Interaction Screening (Cross-Hypothesis GRF)

**Problem**: Generator A's GRF used {container_age, ambient_temp, route_duration} as covariates. Generator B's GRF used
{receiving_delay, dock_type, staffing_level}. Neither GRF saw the other's treatment variable, so neither can discover
that container age's effect depends on receiving delay.

**Mechanism**: For each surviving hypothesis H_i, run a new GRF estimation where:

- Treatment: H_i's treatment variable (same as individual estimation)
- Covariates: all OTHER surviving hypotheses' treatment variables + shared confounders (variables that appear in
  multiple hypotheses' DAGs)

This asks: "does the causal effect of container age vary as a function of receiving delay, dispatch schedule, and the
other validated treatment variables?"

GRF's variable importance output identifies which other treatments drive heterogeneity in H_i's effect. If
`receiving_delay` ranks high as a heterogeneity driver for `container_age`'s CATE, these two hypotheses interact. If
`dispatch_schedule` doesn't appear, it's likely independent of `container_age`.

**Output**: Sparse interaction graph. Each edge represents a pair of hypotheses where one's treatment modifies the
other's effect. Most pairs will NOT interact — the graph is sparse by construction because genuinely interacting causes
are rarer than independent ones.

**Cost**: O(N) GRF runs for N surviving hypotheses. Each run uses the already-derived feature sets from Phase 4.1 — no
new SQL or preprocessing needed. The treatment residuals from DML cross-fitting are reusable.

### 4.5.2 Interaction Functional Form (Pairwise Estimation)

For each interacting pair identified in 4.5.1, characterize the interaction type.

**Multi-treatment DML**: Residualize both treatment variables against shared confounders simultaneously. Regress outcome
residuals on both treatment residual vectors plus their product term. The product coefficient tells you whether the
interaction is amplifying (positive product) or inhibiting (negative product). But this only captures linear
interaction — it misses thresholds and sign-flips.

**GRF interaction surface**: The richer analysis. For the interacting pair (treatment A, treatment B), estimate the CATE
of A as a continuous function of B's value. The SHAPE of this function reveals the interaction type:

- **Flat CATE curve**: A's effect doesn't depend on B. Independent/additive despite the screening flag — possible false
  positive from screening.
- **Monotonically increasing**: amplifying. Higher B makes A's effect stronger. Old containers are worse, and being at a
  slow-receiving site makes them even worse. Each reinforces the other.
- **Monotonically decreasing**: inhibiting. Higher B weakens A's effect. Redundant safety — fixing either one captures
  most of the benefit.
- **Crosses zero**: sign-flip. A's effect reverses depending on B's level. Your dumping example: negative for small
  firms, positive for large firms. The average effect of A is meaningless without knowing B.
- **Nonlinear / U-shaped / threshold**: complex interaction. A's effect might be independent of B below a threshold,
  then amplifying above it. Old containers don't interact with receiving delay when delay is under 15 minutes (
  everything is fine), but above 30 minutes the interaction becomes strongly amplifying (both mechanisms compound).

**Output per interacting pair**: the CATE surface (a function, not a single number) plus classified interaction type.

### 4.5.3 Joint DAG Construction

**[TODO: Formal framework needed — this section describes the target design, not a fully specified mechanism]**

Individual hypotheses have their own DAGs. The composition step requires merging these into a joint DAG that captures
cross-hypothesis causal structure.

**What needs to be resolved**:

*DAG consistency checking*: When two hypotheses share variables, their edges must be consistent. If hypothesis A says
`ambient_temp → receiving_delay` (hot weather slows receiving) and hypothesis B says `receiving_delay → ambient_temp` (
slow receiving exposes shipments to ambient conditions), these represent different causal claims. Mechanical detection:
find all variable pairs that appear in multiple DAGs and check edge direction consistency. Conflicts require
arbitration — either by the judge, by running the estimation with each direction and comparing, or by a dedicated DAG
arbitration step.

*Cross-hypothesis edge discovery*: Even without conflicts, the individual DAGs may be missing edges that connect them.
Dispatch schedule might affect receiving delay (late dispatch → arrival during shift change → understaffed receiving).
Neither individual hypothesis includes this edge because each generator only saw its own entity neighborhood. The
association screening from Phase 2.4 (cross-entity path discovery) provides candidates — but needs to be re-run
specifically for treatment-variable-to-treatment-variable associations across hypotheses.

*Mediation structure between hypotheses*: Hypothesis A's treatment might be a mediator for hypothesis B's treatment. If
fleet generation (hypothesis B) affects container insulation quality, and container age (hypothesis A) also affects
insulation quality, then insulation quality mediates both — but the individual hypotheses may have handled it
differently (one controlled for it, the other didn't). The joint DAG must specify the full mediation structure to
determine which effects are total, direct, and mediated.

### 4.5.4 Mediation Analysis

**[TODO: Formal framework needed — critical for intervention planning]**

Once the joint DAG establishes which variables mediate between hypotheses, formal mediation analysis decomposes total
effects into direct and mediated components. This answers: "if I fix receiving infrastructure but can't replace
containers, how much of the container age effect disappears?"

**What needs to be specified**:

*Mediation estimation method*: Natural direct and indirect effects (Pearl, 2001; VanderWeele, 2015). The framework
extends DML — residualize on confounders, then decompose the treatment→outcome path into treatment→mediator→outcome (
indirect) and treatment→outcome (direct, not through mediator). Multi-treatment DML from 4.5.2 provides the machinery;
the mediation framework determines which residualizations to run.

*Cross-hypothesis mediation*: When hypothesis A's treatment mediates hypothesis B's effect (or vice versa), the
mediation decomposition must respect both hypotheses' DAG structures simultaneously. This is non-trivial because each
hypothesis was originally estimated with different confound sets.

*Sequential versus parallel mediation*: Some mediating chains are sequential (A → M₁ → M₂ → Y). Others are parallel (A →
M₁ → Y and A → M₂ → Y). The decomposition method differs. The joint DAG determines which structure applies, but the
estimation must handle both.

*Interaction within mediation*: The mediated effect itself might be heterogeneous — the amount of A's effect that flows
through mediator M might depend on another variable. GRF on the indirect effect estimates this, but the statistical
framework for heterogeneous mediation is more complex than heterogeneous direct effects.

**Target output**: Per treatment pair in the joint DAG, a decomposition:

```
Total effect of container_age on excursion: 0.3pp/month
  Direct effect (not through insulation): 0.05pp/month
  Mediated through insulation_degradation: 0.25pp/month
    Of which: amplified by receiving_delay > 20min: +0.15pp/month additional
    
Intervention implication:
  Replace containers alone: captures full 0.3pp/month
  Fix receiving alone: captures 0.15pp/month (the amplification component)
  Fix both: captures 0.45pp/month (more than sum due to interaction)
```

### 4.5.5 Composite Output

The full composition output is a **causal interaction landscape**:

1. **Individual effects**: validated per-hypothesis causal estimates from Phase 4.1 (unchanged)
2. **Interaction map**: sparse graph of which hypothesis pairs interact, with functional form classification (
   additive/amplifying/inhibiting/sign-flip/threshold)
3. **Interaction surfaces**: per interacting pair, the CATE of each treatment as a function of the other's value
4. **Joint partial effects**: from multi-treatment DML, the effect of each treatment holding all others at their
   residualized values
5. **[TODO] Mediation decomposition**: total/direct/indirect effects for each treatment through each mediating pathway
   in the joint DAG
6. **[TODO] Intervention priority ranking**: mechanically derived from the interaction landscape — which interventions
   have the highest marginal return, which pairs of interventions have super-additive returns

This feeds into Phase 5 evaluation (specification sensitivity on the joint model) and Phase 6 interpretation (
advocate/prosecutor pairs now argue from both individual and composite evidence).

### Design Constraints

**Composition does not replace individual estimation.** Individual hypotheses have cleaner identification arguments, are
independently challengeable by skeptics, and produce more trustworthy point estimates. Composition is synthesis that
builds on validated components. If the joint model produces a different estimate than an individual model, the
individual model's estimate is preferred for that specific treatment — the joint model's value is the interaction
structure and mediation decomposition, not overriding individual estimates.

**Composition identification is weaker.** The joint DAG combines assumptions from all individual hypotheses. Any
assumption failure in any component can corrupt the joint model. The composition output carries an explicit caveat: "
joint model assumes all individual DAGs are correctly specified. Sensitivity of composite estimates to individual DAG
misspecification is [reported]."

**Not all hypothesis pairs are worth composing.** Only pairs flagged by the interaction screening (4.5.1) proceed to
full joint estimation (4.5.2+). Independent causes get reported as additive with their individual estimates — no
composition needed.

---

## Phase 5: Evaluation

Primarily mechanical. Evaluates causal model outputs and robustness.

### 5.1 Causal Effect Assessment

Per hypothesis, from parallel estimation output:

- **Primary strategy estimate**: point estimate + CI from strongest quasi-experimental design (if available)
- **DML estimate**: universal fallback point estimate + CI (always available)
- **Estimate concordance**: do primary and DML estimates agree in direction and magnitude? Concordance strengthens
  confidence; divergence flags identification sensitivity.
- **GRF heterogeneity**: if run, range of conditional treatment effects across subpopulations + strongest moderator
  variables
- **Direction match**: does observed direction match hypothesis's expected_direction?
- **DoWhy refutations**: placebo, random cause, subset stability
- **Method-specific diagnostics**: pre-trend tests (DiD), density tests (RDD), weak instrument tests (IV), cross-fit
  stability (DML)

### 5.2 Skeptic Correction Integration

Side-by-side comparison:

- Original causal estimate vs corrected estimate(s) after skeptic specification changes
- Delta per correction (which specification change had most impact)
- Skeptic comments (weak signal, for interpreter context)

### 5.3 Specification Sensitivity (Mechanical)

Automated grid over specification variants — no LLM:

- Run causal model with different control variable subsets
- Run with different graph edge configurations (drop each edge, add plausible edges)
- Run with different preprocessing parameters (K=2 vs K=3 clusters, different feature subsets)
- **Multi-timescale sensitivity**: Run estimation with multiple analysis windows (1-year rolling, 2-year rolling, full
  period). For temporal designs, CausalImpact/DiD with different pre/post windows. For DML, subset by time period. If
  effect estimate changes sign across windows → flag "temporally unstable, direction depends on timescale." Catches
  relationships that invert across timescales (e.g., short-run inhibitor that becomes long-run amplifier).
- **Cross-estimator concordance**: Compare primary strategy estimate vs DML estimate. If estimates diverge
  substantially, the causal conclusion depends on identification assumptions — report as specification-sensitive.
- Report stability of causal estimate across variants

If estimate is stable across specifications → robust finding. If it swings wildly → specification-dependent, low
confidence.

**Literature**: Runge et al. (2019) PCMCI framework with sliding window causal discovery.

### 5.4 Quasi-Experimental Verification

**This is the primary evidence for causation.** All other checks provide circumstantial support.

#### Structural Break Detection

For each hypothesis's key variables, detect change-points in temporal data (Bai-Perron or PELT). Identify episodes where
the factor space shifted naturally.

#### Hypothesis Prediction Matching

Using the hypothesis's causal model, predict what outcome change SHOULD have occurred at each structural break. Compare
predicted vs actual: magnitude, direction, timing with lag allowance.

**Multivariate breaks**: When multiple factors co-move at a break, check whether observed outcome is consistent with
single-factor prediction or requires multi-factor explanation. Discriminates between competing hypotheses.

#### Applicability Threshold

Requires temporal data with sufficient history and detectable structural breaks. If unavailable, triggers explicit
causal evidence limitation flag.

### 5.5 Causal Evidence Tiers

**Tier 1 — Quasi-experimentally verified**: Structural breaks detected, hypothesis predictions match observed outcomes.
Prescriptive claims supported with confidence bounds.

**Tier 2 — Circumstantially supported**: No quasi-experimental verification, but hypothesis survives skeptic challenges,
refutations pass, specification is stable. System explicitly states: "Associational evidence consistent
with [hypothesis], but causal mechanism not verifiable from available data."

**Tier 3 — Associational only**: Causal evidence insufficient. System explicitly states: "Statistical association
detected. Insufficient evidence to distinguish causation from correlation."

**No hedging.** Tier is prominently displayed and available to all Phase 6 agents.

### 5.6 Robustness Checks (Mechanical)

**Unmeasured confounding sensitivity** (from PipelineSpec `unmeasured_confounding` field):

**E-values** (VanderWeele & Ding, 2017): Minimum strength of association an unmeasured confound must have with BOTH
treatment AND outcome to fully explain away the observed effect. More interpretable than Rosenbaum's gamma because it's
on the risk ratio scale. Computed per estimation variant as configured in PipelineSpec. Mandatory for all primary
variants.

**Rosenbaum sensitivity bounds**: How strong an unmeasured confound would need to be to nullify the result. Gamma
threshold per configured variant. Complementary to E-values — Rosenbaum bounds apply to matched/stratified designs,
E-values to general estimates. At least E-value required for sensitivity/scoped variants.

**Replication via random splits**: Split data using entity relationship graph for top-level aggregate identification.
Re-run causal model on each subset. Report variance in effect estimates. Minimum cardinality threshold applies.

**Temporal stability** (if temporal data): Hold out recent period, re-run causal model. Delta between full and holdout
estimates. Instability is information, not automatic red flag.

**Pearl's backdoor criterion**: Verify hypothesis conditioning set against FK dependency graph + Phase 2 interaction
structure. Flag collider bias or insufficient adjustment.

### 5.7 Residual Diagnostics and Three-Tier Confound Detection

**Problem addressed**: Causal tools produce valid estimates GIVEN assumptions, but assumptions themselves can be wrong
in ways the tools cannot detect. Three detectability categories exist, requiring different response strategies.

#### Tier 1: Mechanical Detection (automated, cheap)

After Phase 4 causal model execution, test residuals for:

- **Temporal autocorrelation**: Durbin-Watson, Ljung-Box tests. Persistent patterns indicate omitted temporal confounds.
- **Structural breaks in residuals**: CUSUM, Bai-Perron tests. Unexplained regime changes during analysis window.
- **Correlation with unused schema fields**: All fields not in the causal model, including Scout-identified measurement
  metadata (Phase 1.1). If residuals correlate with a measurement metadata field (sensor install date, firmware version,
  calibration schedule) → flag "possible instrumentation confound."
- **Temporal clustering**: If residuals concentrate in specific time windows, report the window boundaries for Tier 2
  investigation.

#### Tier 2: Domain Researcher Targeted Search (medium cost)

When Tier 1 detects unexplained residual patterns:

1. Residual diagnostics provide specific time window and affected entities
2. Scout has identified geospatial anchors and temporal fields (Phase 1.1)
3. Domain Researcher gets SECOND pass targeted by `[location] + [time window] + [domain event types]`

Example: residual spike Iowa watershed 2019-2020 → search "Iowa watershed drought flood extreme weather 2019 2020" →
candidate confound with testable prediction.

Covers publicly documented external events: weather anomalies, regulatory changes, economic shocks, infrastructure
incidents. If candidate confound found → add as covariate, re-run causal model to test whether residual pattern
resolves.

#### Tier 3: User Inquiry (last resort)

When Tier 1 detects patterns AND Tier 2 search returns nothing relevant:

- Present SPECIFIC question derived from residual pattern
- NOT "do you remember significant events?" (too broad)
- Example: "Model explains 73% nitrate variation, but unexplained spike years 6-7 affecting all sub-catchments
  uniformly. Does this correspond to any event — internal policy change, data migration, organizational shift?"

If user provides explanation → add to model, re-run. If user says nothing → report as honest limitation.

**Evidence hierarchy**: Tier 1 (cheap, automated) → Tier 2 (medium cost, web search) → Tier 3 (high cognitive burden on
user). System escalates only as needed.

### 5.8 Range Restriction and Reliability Reporting

Mechanical checks before reporting causal estimates:

- **Variance check**: Treatment variable coefficient of variation. Low variance → limited information for causal
  identification.
- **Collinearity check**: VIF between treatment and confounds. VIF > 10 → treatment effect poorly separable from
  confounds.
- **Support overlap check**: Propensity score distribution overlap between treatment and control groups. Poor overlap →
  extrapolation risk.

Reported alongside every causal estimate as reliability caveats:

```
Causal estimate: fertilizer reduction → 12% nitrate decrease (CI: 8-16%)
Reliability: treatment VIF = 3.2 (acceptable), support overlap = 0.78 (good)
```

When reliability metrics are poor, the system reports the estimate with explicit caveat rather than suppressing it:

```
Effect estimate: +2.1pp (CI: 0.3-3.9pp)
Reliability caveat: treatment variable has limited independent variation
  (VIF with tenure = 14.2). Effect magnitude may be inflated.
```

### 5.9 Nomenclature Externalization Test

**Problem addressed**: When GRF discovers that a treatment effect varies across categorical subgroups (e.g., age effect
differs by insulation type), the pattern may be real or may reflect confounding through the category. Domain literature
provides independently derived coefficients for categorical nomenclature (physical constants, engineering specs,
regulatory thresholds) that serve as external validation of data-discovered patterns.

**Design principle**: Data-first, domain-second. The model discovers interaction patterns from data without domain
coefficients as preprocessing inputs. Domain knowledge is used AFTER estimation to validate whether the discovered
pattern aligns with external physics/engineering/domain theory. This prevents encoding incorrect domain assumptions that
override what the data actually shows (e.g., literature says VIP degrades at 2.5%/year but this specific fleet
experiences 4%/year due to climate or handling conditions).

**Mechanism**:

1. **GRF estimates type-conditional CATEs** from data alone. Produces: for each categorical value, the treatment effect
   slope with CI.
2. **Domain Researcher retrieves published coefficients** for the ENUM values in the categorical variable. For physical
   nomenclature (insulation types, refrigerant classes, packaging grades), manufacturer specs and standards bodies
   provide concrete numeric properties.
3. **Ranking comparison**: do the data-discovered slopes follow the same ORDERING as the domain-predicted coefficients?
   Ranking match confirms the mechanism. Ranking mismatch flags either: domain coefficients don't apply to this
   population, or a confound the model isn't capturing.
4. **Ratio comparison**: is the MAGNITUDE ratio between the strongest and weakest type-conditional effects consistent
   with domain predictions? Data says XPS slope is 2.8× VIP slope; domain predicts 4× based on degradation rates. Rough
   consistency (within 3×) supports the mechanism. Large divergence warrants investigation.

**Output**:

```
ExternalizationResult {
    discovered_ranking: List[str]          # types ordered by effect magnitude
    domain_ranking: List[str]              # types ordered by domain coefficient
    ranking_match: bool
    discovered_ratio: float                # strongest / weakest effect
    domain_ratio: float                    # from published coefficients
    ratio_consistent: bool                 # within 3× of each other
    interpretation: "confirmed" | "divergent" | "insufficient_data"
}
```

**Scope**: Applies whenever GRF heterogeneity analysis involves a categorical moderator whose values map to externally
documented properties. Not applicable to opaque categories with no published coefficients. The Domain Researcher
determines whether external coefficients exist for a given ENUM; if not, this step is skipped.

---

## Phase 6: Interpretation

### 6.0 External Validity Contextualization

**Agent**: Domain Researcher (second pass, neutral — no hypothesis allegiance).

**Role**: Contextualize findings against external knowledge. "Is this effect known to be time-bound? Did market
conditions change during the data period? Are there domain-specific generalizability concerns?"

**Output**: External validity context document, available as read-only input to all Phase 6 agents. Neutral framing —
presents relevant context without favoring or opposing any hypothesis.

**Rationale**: Moved from Advocates (original design) because Advocates are incentivized to cherry-pick confirming
external evidence. Domain Researcher is neutral and already has web search infrastructure.

### 6.1 Per-Hypothesis Advocate + Prosecutor Pairs

**Count**: One Advocate and one Prosecutor per hypothesis that is not clearly disproven (causal effect not significant
or early-terminated).

**Both receive identical read-only inputs**:

- Causal effect estimates: primary strategy + DML fallback + concordance assessment
- GRF heterogeneity results (if applicable)
- DoWhy refutation results
- Method-specific diagnostics (pre-trend, density, weak instrument tests)
- Skeptic correction deltas with credibility levels
- Specification sensitivity results (stability across variants, cross-estimator concordance)
- Quasi-experimental verification results and causal evidence tier (Tier 1/2/3)
- Robustness metrics (E-values, Rosenbaum bounds, replication stability, temporal stability, backdoor criterion)
- **Phase 4.5 composition results**: interaction map, joint partial effects, interaction surfaces for this hypothesis's
  interactions with other hypotheses
- External validity context (from 7.0)
- Read-only summary of all other hypotheses' causal estimates
- Neither generates new evidence. Both argue from provided results.

**Advocate**: "Make the strongest case FOR this hypothesis given the evidence."

Exploits affirmative bias toward supporting the hypothesis. Contextualizes weak spots favorably — "confound delta of
0.15 is within noise given the sample size" — and emphasizes strong mechanical results.

**Prosecutor**: "Make the strongest case AGAINST this hypothesis given the evidence."

Exploits affirmative bias toward disproving the hypothesis. Emphasizes skeptic corrections, robustness weaknesses, low
E-values — "E-value of 1.4 means even a modest unmeasured confound eliminates this effect" — and cross-estimator
divergence when present.

**Neither has web search or tool access.** Pure argumentation from provided evidence.

### 6.2 Judge

**Role**: Reconcile N × (Advocate + Prosecutor) report pairs against mechanical evidence to answer the user's original
question.

**Receives**: All advocate reports, all prosecutor reports, all mechanical results, **causal interaction landscape from
Phase 4.5** (interaction map, joint effects, interaction surfaces), external validity context, original user query,
Domain Researcher context.

**Task**: For each hypothesis, weigh adversarial arguments against mechanical evidence. Rank hypotheses by explanatory
value. Identify which combination best answers the question. Use Phase 4.5 interaction landscape to synthesize
multi-causal conclusions: which interventions are additive, which are super-additive, which subpopulations require
different intervention strategies. Synthesize actionable conclusions.

**Key constraint**: Judge works from balanced adversarial input — every hypothesis has been both championed and attacked
using the same evidence. Harder for any single bias to dominate the final output.

### 6.3 User Output

Final output presents:

- Per-hypothesis: primary strategy estimate + DML estimate with confidence intervals, identification strategy used,
  cross-estimator concordance
- Per-hypothesis: **causal evidence tier (Tier 1/2/3) with explicit limitation statement if Tier 2 or 3**
- Per-hypothesis: GRF heterogeneity results (conditional treatment effect range, strongest moderators) if applicable
- Per-hypothesis: quasi-experimental verification results (if available)
- Per-hypothesis: skeptic specification corrections and their impact on causal estimates
- Per-hypothesis: specification sensitivity (stability across variants, including multi-timescale and cross-estimator)
- Per-hypothesis: advocate and prosecutor summaries
- Per-hypothesis: robustness metrics (E-values, Rosenbaum bounds, replication stability, temporal stability, backdoor
  criterion)
- Per-hypothesis: method-specific diagnostics (pre-trend, density, weak instrument, cross-fit stability)
- Per-hypothesis: **reliability caveats** (VIF, support overlap, variance — from Phase 5.8)
- Per-hypothesis: **explicit assumptions block** (see below)
- External validity context
- **Cross-hypothesis: causal interaction landscape** (from Phase 4.5)
    - Interaction map: which hypothesis pairs interact, functional form per pair
    - Joint partial effects from multi-treatment DML
    - Interaction surfaces: CATE of each treatment as function of interacting treatments
    - [TODO] Mediation decomposition: total/direct/indirect per pathway
    - [TODO] Intervention priority ranking: marginal return per intervention, super-additive pairs
- Cross-hypothesis: Judge's reconciliation
- Clear separation of mechanical/ML results vs. LLM interpretation

#### Assumptions Block

Every causal estimate carries an explicit assumptions block listing what the estimate relies on. Generated mechanically
from hypothesis specification + threat taxonomy:

```
Causal estimate: fertilizer reduction → 12% nitrate decrease (CI: 8-16%)
Tier: 1 (quasi-experimental verification available)

Assumptions:
- No unmeasured confounders beyond causal graph [standard]
- No treatment spillover between parcels [PCS-8 class: unverifiable from data]
- Treatment assignment not correlated with transient outcome spikes [PCS-9 class: unverifiable]
- Measurement process unchanged during analysis period [PCS-4 class: verified via Tier 1-2]
- Outcome metric definition stable [PCS-10 class: unverifiable from data]
```

Assumptions classified as: *verified* (mechanically checked), *partially verified* (Tier 2 search found no
contradiction), or *unverifiable from data* (epistemological limit — user must assess plausibility). The system does not
claim to verify what it cannot verify.

**For Tier 2/3 hypotheses, the causal limitation statement is mandatory and prominent.**

User can override any LLM interpretation while retaining causal model results.

---

## Hypothesis Specification

### Design Principle

A hypothesis is a causal model specification — the inputs to the Executor Compiler, which translates them into a
PipelineSpec for the mechanical engine. The engine wraps DML/GRF estimation in DoWhy's identification and refutation
framework. Evaluation is delegated to established causal inference tooling, not a custom evaluation engine. The system's
job is producing well-specified causal models via the Compiler; the engine's job is executing them deterministically.

### Hypothesis Structure

```json
{
  "description": "Discount frequency causes churn, independent of customer tenure",
  "treatment": {
    "variable": "discount_freq",
    "derivation": "quarterly count of discounted orders per customer",
    "source_entities": [
      "orders",
      "customers"
    ]
  },
  "outcome": {
    "variable": "churn",
    "derivation": "customer inactive for 90+ days",
    "source_entities": [
      "customers",
      "activity_log"
    ]
  },
  "causal_graph": {
    "edges": [
      [
        "discount_freq",
        "churn"
      ],
      [
        "tenure",
        "churn"
      ],
      [
        "tenure",
        "discount_freq"
      ]
    ],
    "cross_entity_paths": [
      {
        "path": [
          "parcel.fertilizer_rate",
          "soil.no3",
          "stream.no3"
        ],
        "source": "Phase 2.4 path enumeration",
        "association_strength": [
          0.72,
          0.65
        ]
      }
    ]
  },
  "expected_direction": "positive",
  "confounds": [
    "tenure",
    "acquisition_channel"
  ],
  "independently_actionable_mediators": [
    "ins_type_enc"
  ],
  "ecological_fallacy_dimensions": [
    {
      "variable": "facility_id",
      "within_group_effect": "0.45pp",
      "marginal_effect": "0.86pp",
      "reduction_pct": 47
    }
  ],
  "derived_features": [
    {
      "name": "dispatch_month",
      "derivation": "EXTRACT(MONTH FROM date)",
      "grain": "medium_temporal"
    },
    {
      "name": "dispatch_year",
      "derivation": "EXTRACT(YEAR FROM date)",
      "grain": "coarse_temporal"
    }
  ],
  "enrichment_joins": [
    {
      "target_entity": "facilities",
      "join_key": "facility_id",
      "columns": [
        "has_redundancy",
        "system_type"
      ]
    }
  ],
  "preprocessing": {
    "clustering": {
      "method": "behavioral",
      "variables": [
        "purchase_pattern",
        "engagement_score"
      ]
    },
    "feature_derivation": [
      {
        "name": "discount_freq",
        "sql_sketch": "COUNT of discounted orders per customer per quarter"
      },
      {
        "name": "tenure",
        "sql_sketch": "months since first order"
      }
    ]
  }
}
```

### Key Components

**Treatment + Outcome**: What causes what. Includes derivation logic — how abstract concepts map to concrete schema
queries. This is where Executor translates to SQL.

**Causal graph**: DAG of variable relationships. Fed to DoWhy for identification strategy verification (backdoor
criterion, frontdoor, IV validity) and to the identification scanner for strategy selection. May include cross-entity
paths from Phase 2.4 — the executor flattens these via SQL joins, but the join strategy itself is a specification choice
that skeptics can challenge. This is the primary object of skeptic challenge.

**Preprocessing**: Clustering, dimensionality reduction, feature derivation. Determines how raw schema is transformed
before causal analysis. Second object of skeptic challenge.

**Expected direction**: Generator's prediction. Compared against causal model output.

### Hypothesis Discrimination

Two hypotheses are **structurally distinct** if their causal model inputs differ in any of:

- Different treatment variable(s)
- Different causal graph structure (different edges, not just different edge weights)
- Different preprocessing specification (different clustering, different derived features)
- Different confound set

Two hypotheses that share identical structure but differ only in expected magnitude or threshold literals are **the same
hypothesis**. The causal model will determine the actual magnitude — there's nothing to discriminate.

**Example of distinct hypotheses for same outcome (churn):**

- H1: treatment=discount_freq, graph includes discount_freq→churn
- H2: treatment=support_quality, graph includes support_quality→churn
- H3: treatment=discount_freq, same as H1 but preprocessing clusters by cohort rather than behavior

H1 and H3 are distinct (different preprocessing may yield different causal estimates). H1 with "large effect" vs H1
with "moderate effect" is redundant.

### Non-Redundancy Check

Mechanical comparison of hypothesis specifications:

1. Compare graph edge sets (ignoring order)
2. Compare treatment/outcome pairs
3. Compare preprocessing specifications structurally
4. If all three match → redundant, merge

---

## Agent Summary

| Agent                              | Phase | Count               | LLM?           | Tool Access                                      | Role                                                                          |
|------------------------------------|-------|---------------------|----------------|--------------------------------------------------|-------------------------------------------------------------------------------|
| Survey Scout                       | 1     | 1                   | Yes            | Data query                                       | Map data landscape + geospatial anchors + measurement metadata                |
| Domain Researcher                  | 1     | 1                   | Yes            | Web search + data query                          | Map knowledge landscape, establish reference classes                          |
| Anchor Entity Selector             | 1     | 1 call              | Yes            | None                                             | Identify non-target anchor entities for generators                            |
| Generator (iterative stability)    | 2     | 2-3                 | Yes            | Stability selection + SHAP + data query          | Iterative stability loop: run → interpret → strip/flip → rerun                |
| Stage 1 Skeptic                    | 2     | 1 per generator     | Yes            | Stability selection + data query                 | Challenge strip/flip decisions with counter-stability analyses                |
| Cross-Entity Path Discovery        | 2     | 0                   | No             | N/A                                              | BFS path enumeration + DP-constrained association screening                   |
| Heterogeneity Check                | 2     | 0                   | No             | N/A                                              | Residual clustering + SHAP interaction for subpopulations                     |
| Threat Assigner                    | 3     | 1                   | Partial        | None                                             | Map hypothesis structure to Campbell threats                                  |
| D-Separation Refinement            | 3     | 0                   | No             | N/A                                              | Test DAG conditional independence claims against data, add missing edges      |
| **Executor Compiler**              | **4** | **1 per hyp**       | **Yes (Opus)** | **Data query (executeQuery, analyzeExpression)** | **Translate hypothesis spec → PipelineSpec (typed contract)**                 |
| **Mechanical Engine**              | **4** | **1 per hyp**       | **No**         | **ML tools (DML, GRF, DoWhy, EconML)**           | **Execute PipelineSpec deterministically**                                    |
| Stage 2 Skeptic (pass 1, blind)    | 4     | N per threat        | Yes            | None                                             | Generate PipelineSpec concerns                                                |
| Stage 2 Skeptic (pass 2, informed) | 4     | N per threat        | Yes            | Data query + recompilation                       | Re-compile PipelineSpec with corrections                                      |
| Interaction Screening              | 4.5   | 0                   | No             | ML tools (GRF)                                   | Cross-hypothesis GRF: detect which hypothesis pairs interact                  |
| Joint Estimation                   | 4.5   | 0                   | No             | ML tools (DML + GRF)                             | Multi-treatment DML + interaction surface estimation for interacting pairs    |
| [TODO] DAG Merge                   | 4.5   | Partial             | TBD            | N/A                                              | Joint DAG construction, consistency checking, cross-hypothesis edge discovery |
| [TODO] Mediation Decomposition     | 4.5   | 0                   | No             | ML tools                                         | Total/direct/indirect effect decomposition per pathway in joint DAG           |
| Specification Sensitivity          | 5     | 1                   | No             | ML tools                                         | Automated grid over specification variants + cross-estimator concordance      |
| Quasi-Experimental Check           | 5     | 1                   | No             | N/A                                              | Structural break detection + prediction matching                              |
| Robustness Checks                  | 5     | 1                   | No             | N/A                                              | E-values, Rosenbaum bounds, replication, temporal stability, backdoor         |
| Residual Diagnostics               | 5     | 1                   | No             | N/A                                              | Temporal autocorrelation, structural breaks, measurement metadata correlation |
| Domain Researcher (Tier 2 search)  | 5     | 0-1                 | Yes            | Web search                                       | Targeted search for external events matching residual patterns                |
| Range Restriction Check            | 5     | 1                   | No             | N/A                                              | VIF, support overlap, variance — reliability caveats                          |
| Domain Researcher (2nd pass)       | 6     | 1                   | Yes            | Web search                                       | External validity contextualization (neutral)                                 |
| Advocate                           | 6     | N per surviving hyp | Yes            | None (reads results)                             | Best-case interpretation                                                      |
| Prosecutor                         | 6     | N per surviving hyp | Yes            | None (reads results)                             | Worst-case interpretation                                                     |
| Judge                              | 6     | 1                   | Yes            | None (reads reports)                             | Reconcile adversarial reports against causal evidence                         |

**Model tier assignments**: Generator = Opus (highest reasoning for causal decisions). Executor Compiler = Opus (DAG
construction, confounder reasoning). Scout = Sonnet (breadth-first data profiling). Advocate/Prosecutor = Haiku (
presentation layer, no analytical decisions). Presentation layer rewrites outputs in character voice with strict
constraint to preserve numbers exactly.

---

## Key Design Decisions and Rationale

### Why multiple generators instead of one planner?

Single planner produces hypotheses from one cognitive starting point. Same model, same inputs produces same RLHF-favored
outputs regardless of diversity instructions (Liang et al., 2023). Entity cluster anchoring causes different information
ordering, producing genuinely different hypotheses.

### Why empirical factor discovery before hypothesis generation?

Generators guessing which factors matter produces hypotheses grounded in LLM pretraining biases, not the actual data.
Stability selection surfaces what ACTUALLY predicts the outcome. In the current design, factor discovery and hypothesis
generation are unified — the generator's iterative stability loop IS both. The Tukey principle (explore then
hypothesize) is preserved: each stability run is exploration, each strip/flip decision is hypothesis formation.

### Why hypothesis = causal model specification, not predicate grammar?

Evaluation is delegated to established causal inference tools (CausalImpact, DoWhy). These tools need model
specifications (treatment, outcome, graph, controls), not boolean predicate trees. A custom evaluation engine duplicates
what well-tested ML tools already do. The hypothesis grammar matches what the execution tooling consumes.

### Why structural discrimination instead of predicate-based?

Two hypotheses with identical causal graph, treatment, outcome, and preprocessing produce identical causal estimates
regardless of what the generator predicted about magnitude or direction. The model determines actual values. Hypotheses
are distinct only if they specify structurally different causal models.

### Why CausalImpact/DoWhy instead of custom causal evaluation?

Custom evaluation reimplements what these tools provide with better statistical foundations. CausalImpact handles
Bayesian structural time series with counterfactual projection for single treated units over time. DoWhy provides
DAG-based identification, multiple estimation backends, and built-in refutation tests. Both are peer-reviewed and
well-tested. DoWhy serves as the identification and refutation framework wrapping all estimators (DML, IV, RDD
backends).

### Why DML as universal estimation backbone?

Double Machine Learning (Chernozhukov et al. 2018) reuses Phase 2's LGBM/ensemble models as first-stage nuisance
function estimators — the models built for factor discovery serve double duty for causal estimation. This eliminates the
gap where Phase 2 models were effectively discarded when causal estimation began. DML handles high-dimensional
confounding semiparametrically, requires no functional form assumptions, and always runs regardless of whether a
stronger quasi-experimental design is available — providing a universal comparison point across all hypotheses.
Cross-fitting ensures valid inference despite ML first stage.

### Why an identification strategy scanner before estimation?

The architecture previously said "detect which quasi-experimental structures exist" (P40) but didn't specify HOW. The
scanner makes this concrete and mechanical: check for threshold-based assignment (RDD), instruments (IV), staggered
adoption (DiD/SDiD), temporal single-unit design (CausalImpact). Pattern matching on data structure, not LLM judgment.
When multiple strategies apply, all run in parallel — concordance across estimators strengthens confidence, divergence
exposes identification sensitivity.

### Why GRF for heterogeneity instead of cluster-then-estimate?

Phase 2.5's mandatory heterogeneity check detects subpopulations. The previous approach was to cluster, then run
separate causal models per cluster. Generalized Random Forests (Athey, Tibshirani & Wager 2019) estimate conditional
average treatment effects directly as a continuous function of covariates — no discrete subgroup boundaries needed, with
valid confidence intervals for individual-level effects. More principled than arbitrary clustering boundaries, and the
forest structure itself reveals which covariates drive heterogeneity.

### Why skeptics challenge model specification, not query results?

The causal conclusion depends on whether the model specification is correct: right control variables, right graph, right
preprocessing. Query-level errors matter only insofar as they affect model inputs. Specification skeptics challenge the
assumptions that determine the conclusion. Skeptics operate post-ML because re-running the causal model with corrected
specification is the only way to objectively quantify their impact.

### Why quasi-experimental verification is mandatory, not optional?

Without it, the system cannot distinguish causation from correlation. All other checks provide circumstantial evidence.
Only quasi-experimental verification tests whether a hypothesis correctly predicts outcomes from naturally occurring
changes. When unavailable, the system must explicitly state the limitation.

### Why per-hypothesis Advocate + Prosecutor instead of Advocates + meta-Critic?

Single meta-Critic challenging the Judge's reconciliation is too late: biased advocacy has already shaped the Judge's
reasoning. Per-hypothesis Prosecutor ensures every hypothesis faces adversarial challenge before reaching the Judge.
Both receive identical read-only evidence.

### Why strip web search from Advocates and Prosecutors?

Advocates with web search cherry-pick confirming external sources. External validity moved to Domain Researcher (
neutral, no hypothesis allegiance).

### Why no explicit "planner" agent?

The execution plan derives from hypothesis specifications. Treatment/outcome define what to compute, graph defines the
model, preprocessing defines transformation steps. Mechanical derivation from structured specifications.

### Why refiners measure instead of judge?

"Is this too vague?" invites affirmative bias. "Estimate specificity level" produces a measurement that downstream logic
thresholds mechanically. The refiner cannot rubber-stamp because it's not asked to approve.

### Why exploit affirmative bias instead of fighting it?

Prompting models to "be critical" produces performative disagreement. Prompting them to "PROVE this specification is
wrong" exploits affirmative bias as a rigor engine. Combined with the correction requirement: skeptics must demonstrate
impact through re-running the causal model with corrections.

### Why deterministic correction loop termination?

Delta between original and corrected causal estimates is mechanical. Continue while delta is above threshold and
positive. Stop when delta is non-positive or below threshold. No LLM judges when to stop.

### Why distribute validity types across pipeline stages?

Construct validity caught at generator (Phase 2, via iterative stability interpretation). Internal validity challenged
by skeptics (Phase 2 Stage 1 + Phase 4 Stage 2). Statistical conclusion validity handled by causal tools' built-in
tests. External validity assessed by neutral Domain Researcher (Phase 6). Concentrating all in execution wastes
resources and misses fixable problems.

### Why no calibration agent?

Domain Researcher provides reference class benchmarks. Phase 2 factor discovery provides regression-observed effect
sizes. Refiners validate against these baselines. Causal tools produce calibrated confidence intervals directly.

### Why cross-entity path discovery as mechanical menu, not LLM generation?

LLMs at graph-level causal reasoning achieve F1=29 (Jin et al., 2024 Corr2Cause). Letting generators freely propose
cross-entity paths produces plausible-sounding but empirically unsupported chains. Mechanical path enumeration (BFS on
FK topology) + DP-constrained association screening constrains generators to paths with data support. Multi-level
aggregation sensitivity catches ecological fallacy mechanically.

### Why three-tier confound detection instead of just running more robustness checks?

Causal tools produce valid estimates given assumptions, but can't detect when assumptions are wrong. Nonlinearity scan (
Tier 1) catches what smooth-function tools miss. Residual diagnostics (Tier 1) detect unexplained patterns mechanically.
Domain Researcher targeted search (Tier 2) covers publicly documented external events. User inquiry (Tier 3) addresses
private organizational knowledge. Escalation hierarchy keeps cognitive burden low — user questions are specific ("
unexplained spike years 6-7 in sub-catchments") not broad ("remember any events?").

### Why mandatory heterogeneity check rather than generator-directed?

Generators systematically miss subpopulation structure because they reason about average effects. Residual clustering +
SHAP interaction values surface moderators mechanically. Making it mandatory prevents the common failure mode where
aggregate causal estimates mask opposing subgroup effects (Simpson's paradox). Generators may propose additional
subgroup splits, but baseline heterogeneity check always runs.

### Why explicit assumptions blocks in output?

Treatment spillover, regression to mean, measurement gaming — these are properties of the data generation process,
undetectable from the data itself. The system cannot verify what it cannot observe. Instead of silent assumptions, every
causal estimate lists what it relies on, classified as verified/partially verified/unverifiable. The user decides
whether unverifiable assumptions are plausible for their domain.

### Why generators as iterative stability loops, not two-phase (discover then hypothesize)?

Case study validation revealed that interpreting stability results IS hypothesis generation. "This is a mediator" → flip
target. "This is uncontrollable" → strip. "This has a threshold" → request SHAP. Each decision is a causal commitment.
The original two-phase design (Phase 2: one-shot regression, Phase 3: generators interpret) created an artificial
boundary — generators were doing causal reasoning in Phase 3 that depended on decisions that should have been made
iteratively in Phase 2. Merging them into a single iterative loop matches what the process actually is.

### Why anchor entities instead of entity clusters?

Entity clusters gave generators a neighborhood to explore but no causal constraint. Anchoring on a specific non-target
entity forces a join topology, which constrains the feature set, which IS the causal commitment. A generator anchored on
`node_ops_logs` naturally discovers "before the trip" causes. One anchored on `receiving_logs` naturally discovers "
after the trip" causes. The diversity emerges from entity selection, not from prompting for different perspectives on
the same kitchen-sink regression.

### Why split Executor into Compiler + Engine?

The original Executor made two kinds of decisions: (1) pipeline design decisions (what to estimate, which confounders,
what thresholds) and (2) execution decisions (run this query, fit this model). Only the first requires LLM reasoning.
Mixing them created three problems: the LLM stayed live during long-running ML jobs (which current frameworks handle
poorly), ad hoc decisions during execution were hard to audit, and the same pipeline couldn't be reproduced without
re-running the LLM. The PipelineSpec contract resolves all three. The Compiler makes every judgment call upfront and
captures it in a typed schema. The Engine executes deterministically. Skeptics challenge the spec (the decisions), not
the execution.

### Why codify failure mode formulas rather than leave them to compiler judgment?

Iterative validation across 6 compiler runs showed that certain calculations drift consistently when left to judgment:
PELT penalties (wrong in 3/4 runs before formula was added), d-sep pair selection (noise floor inflated by causally
connected pairs), treatment structural max R² (range 0.05-1.0 across runs). Explicit formulas eliminated these drift
patterns while preserving compiler judgment for genuinely context-dependent decisions (placebo ratio, sanity magnitude
bounds, confounder drop thresholds).

### Why executable filter DTOs instead of prose notes for scoped variants?

The engine cannot execute prose. A variant annotated "Filter: region IN ('Midwest_IN', ...)" in a notes field silently
runs on the full population, producing wrong estimates labeled as subpopulation estimates. The filter DTO
`{column: "region", operator: "IN", values: [...]}` is mechanically executable and unambiguous.

### Why persistent pipeline state for skeptic re-execution?

The original skeptic correction loop required full re-compilation + full engine re-run for every proposed correction.
This was expensive (re-running the entire pipeline for a single W matrix change) and imprecise (the skeptic saw only the
final ATE delta, not which intermediate stages were affected). Persistent state at stage boundaries enables targeted
replay: a W matrix correction invalidates estimation and downstream but preserves the cached query result and d-sep
tests. The skeptic sees deltas at every stage — "removing this variable changed the ATE by X, the E-value by Y, and the
GRF subgroup Z flipped sign." Richer evidence than a single before/after comparison, and cheaper because upstream stages
don't re-execute. Implementation uses Restate's journaled workflow state with one handler per pipeline stage.

### Why unmeasured confounding in PipelineSpec rather than only Phase 5?

E-values and Rosenbaum bounds quantify "how strong would an unmeasured confounder need to be to explain away this
effect?" Without them, a 0.45pp effect could be nullified by a modest unmeasured variable and the pipeline would never
flag it. Making them part of the PipelineSpec ensures they always run, rather than being an optional Phase 5 add-on.

### Why the target entity must not be the anchor?

Anchoring on the fact table (e.g., `shipments`) produces kitchen-sink queries where every column is one join away. No
causal structure is forced — the generator pulls everything and lets LGBM sort it out. The result is a single dominant
regression, not a hypothesis. Requiring non-target anchors forces each generator to commit to a causal direction before
seeing any results.

### Why two-stage skeptics?

Stage 1 (challenge generation) is more important than Stage 2 (challenge execution). A bad strip — removing a variable
from the feature set — corrupts everything downstream permanently. The generator's strip/flip decisions are the
highest-leverage causal commitments in the pipeline. Stage 1 skeptics run counter-stability analyses to empirically test
those decisions. Stage 2 skeptics challenge the executor's quasi-experimental specification, as before. Both stages
require empirical evidence, not verbal objection.

### Why guard against 1:N join fan-out?

When joining across entity levels with 1:N relationships, rows silently multiply and give the N-side entity
disproportionate weight. This is a data engineering bug, not an analytical trap. A generator joining `shipments` to
`node_ops_logs` (logged multiple times per day) inflated 562k to 720k rows without any visible error. The model then
learns a biased signal — overweighting heavily-monitored entities. Generators must verify row count matches target
entity grain after every JOIN.

### Why externalize nomenclature validation rather than enrich nomenclature as preprocessing?

When a categorical variable represents a physical property (insulation type, refrigerant class), domain literature
provides numeric coefficients (thermal conductivity, degradation rates). The temptation is to enrich the data with these
coefficients as preprocessing — replacing the category with a continuous physical variable. This is wrong: if the
published coefficient doesn't match this specific population's behavior (different climate, handling, manufacturing
batch), the derived variable encodes incorrect physics that the model cannot override. Instead: let the trees discover
type-specific effect slopes from data, then compare the discovered pattern against published coefficients as a
validation step. Ranking match confirms the mechanism. Ratio match confirms the magnitude. Divergence flags either
inapplicable domain knowledge or uncontrolled confounding — both valuable findings that preprocessing would have hidden.

### Why broad-then-prune for DAG construction instead of minimal DAGs?

Missing a confounder edge biases the causal estimate. Including an unnecessary edge reduces precision but doesn't bias.
The error costs are asymmetric: under-specification is worse than over-specification. Generators should therefore
propose maximally broad DAGs, skeptics should challenge for missing nodes (which mechanical testing can't find), and
d-separation testing mechanically prunes edges the data doesn't support. This three-step flow (broad LLM → semantic LLM
challenge → mechanical falsification) exploits each component's strength: LLMs for breadth and domain reasoning,
statistical testing for edge validity.

### Why a separate composition phase instead of multi-treatment estimation from the start?

Single-treatment estimation has cleaner identification arguments, is independently challengeable by skeptics, and
produces more trustworthy point estimates. Entity anchoring deliberately creates non-overlapping feature sets for
hypothesis diversity — but this means no single estimation sees the full causal landscape. Composition synthesizes
validated individual components into joint interaction estimates. If the joint model disagrees with an individual model
on a specific treatment's effect, the individual estimate is preferred — the joint model's value is the interaction
structure and mediation decomposition, not overriding individual estimates.

### Why screen for interactions rather than composing all pairs?

With N surviving hypotheses, there are N×(N-1)/2 pairs. Most don't interact — genuinely interacting causes are rarer
than independent ones. Full joint estimation on all pairs wastes computation and multiplies the chance of spurious
interaction findings. GRF-based screening (O(N) runs reusing existing treatment residuals) identifies the sparse subset
of pairs that actually interact, and only those proceed to full joint estimation. Independent pairs are reported as
additive with their individual estimates.

### Why is mediation conditional and informational rather than default and verified?

Mediation decomposition (total = direct + mediated) answers "what mechanism to intervene on" — a different question than
the total effect ("does the treatment work?") or GRF heterogeneity ("for whom does it work?"). But it requires an
additional unverifiable assumption: no unmeasured confounders of the mediator-outcome relationship. The total effect
refutation suite (placebo, random common cause, E-value) tests for treatment-outcome confounders, not mediator-outcome
confounders — these are different paths. Rather than reimplementing DoWhy's refutation suite for each decomposition run,
mediation is triggered conditionally (only when the generator identifies independently actionable mediators), run as a
single additional DML call per mediator (mediator added to confounder set, arithmetic decomposition), and labeled as
informational with an explicit assumption caveat. GRF with categorical moderators is the preferred mechanism
decomposition tool because it operates within the verified total-effect framework.

---

## Open Questions

### Implementation Only

All conceptual questions resolved through design iteration. Remaining items are tuning parameters to be determined
empirically during development:

1. **Generator count**: Dynamic based on overlap escalation. Upper bound TBD.
2. **Refiner iteration limit**: Likely 2-3 rounds.
3. **Judge quality monitoring**: Without meta-Critic, rely on per-hypothesis adversarial structure to constrain bias.
4. **Regressor selection**: Fixed set or dynamic based on data characteristics.
5. **Skeptic tool call budget**: Per-pass limit TBD.
6. **Correction loop threshold**: Minimum delta for continued iteration.
7. **Replication split minimum cardinality**: Below which splitting is unreliable.
8. **Feature overlap threshold**: 75% is placeholder, may need tuning.
9. **Identification scanner thresholds**: How strong must instrument first-stage F be? What density test p-value rejects
   RDD? What pre-trend tolerance for DiD? Scanner parameters to be tuned empirically.
10. ~~**Structural break detection parameters**~~: Resolved — PELT formula: sensitive=log(T), conservative=3×log(T).
11. **Haiku extraction reliability**: PipelineSpec extraction from Compiler output uses Haiku-class model. Field mapping
    accuracy needs validation across hypothesis types.
12. **Engine orchestration**: Robust "park and resume" pattern for long-running ML jobs. Temporal workflows (e.g.,
    Temporal.io) with heartbeating; agent tool calls dispatch workflows and return immediately with workflow ID.
13. **Calibration without ground truth**: How to validate pipeline quality when true causal effects are unknown.
    Currently an open architectural question.
14. **CausalImpact / ITS track**: PipelineSpec currently supports DML/GRF only. Single-unit time series designs (
    Bayesian structural time series) need schema extension: `estimation_variants.model_type: CausalImpact` with pre/post
    period specification. TBD when temporal intervention hypotheses arise.
15. **Preprocessing specification**: Original hypothesis spec included clustering, PCA, dimensionality reduction.
    PipelineSpec has no preprocessing fields — feature engineering happens inside `engineerDerivedFeature` during
    compilation. Add `preprocessing` section to PipelineSpec if needed.
16. **Phase 2 nuisance model reuse**: P48 resolution requires model serialization + PipelineSpec artifact references (
    `nuisance_model_artifacts: [path]`). Deferred until Engine orchestration matures.

### Conceptual — Requiring Design Work

11. **[TODO] Joint DAG construction**: How to merge individual hypothesis DAGs mechanically. Includes: consistency
    checking (contradictory edge directions between hypotheses), cross-hypothesis edge discovery (associations between
    treatment variables from different hypotheses), and arbitration for conflicts (mechanical estimation with both
    directions vs. judge-mediated).
12. **[TODO] Mediation estimation framework**: Natural direct/indirect effects in multi-treatment settings. Must handle:
    sequential vs parallel mediation, intermediate confounding (confounders of the mediator-outcome relationship that
    are affected by treatment), heterogeneous mediation (the mediated effect varies by subpopulation), and interaction
    within mediation pathways. Candidate frameworks: interventional direct/indirect effects (VanderWeele 2015), causal
    mediation with DML (Chernozhukov et al. 2024), GRF-based heterogeneous mediation.
13. **[TODO] Intervention priority ranking**: Mechanical derivation from the interaction landscape. Must account for:
    individual marginal effects, super-additive pair effects (fixing both gives more than sum of fixing each),
    cost-per-intervention (external input), and subpopulation targeting (different interventions optimal for different
    subgroups via GRF CATEs). Target output is a ranked intervention menu for the user, not a single recommendation.
14. **[TODO] Population-level / aggregate context feature generation**: Individual-level estimation (DML, GRF) misses
    effects that only exist at the aggregate level — distributional properties (variance, concentration),
    contagion/network effects, systemic risk accumulation, cyclical dynamics. These are emergent features of the
    collection that no individual observation carries. Current generators, even with entity anchoring, are unlikely to
    reliably invent aggregate-derived features (rolling variance, spectral cycle phase, spatial autocorrelation) because
    the stability loop's attention is anchored on individual-level causal chains.

    *Design constraints from discussion*: (a) Should be a **conditional path**, not mandatory — triggered mechanically
    when structural break detection finds clustered anomalies across entities, or when residual diagnostics show
    correlated residuals across entities within time windows. Without a trigger, individual-level tools are sufficient
    and the population layer adds cost without value. (b) Population effects must be both **statistically visible** (
    enough events and signal strength to surface above noise) and **practically significant** (the aggregate insight
    leads to different interventions than individual-level analysis alone). Rare catastrophic events (one blowout in a
    fleet of hundreds) are neither detectable nor worth detecting at scale. (c) The mechanism should be **interleaved
    with the generator loop**, not run beforehand — it needs to know which individual-level features emerged as
    important before it can meaningfully ask "what population-level patterns of THIS variable matter?" (d) Candidate
    tools: periodogram/spectral analysis for cycle discovery, STL decomposition, Moran's I for spatial autocorrelation,
    distributional statistics at parent-entity grain. The LLM's role is tool selection and grouping-variable selection,
    not open-ended feature invention. (e) DAG delta termination prevents oscillation — same mechanism as skeptic
    correction loop. (f) An **advisor agent** anchored to the same entity as the generator but prompted for
    population-level reasoning is the likely mechanism. Entity anchoring constrains it to concrete inspection rather
    than open-ended domain reasoning.

---

## Design Evolution

This section traces the complete design history across five iterative sessions, documenting every problem identified
with the initial architecture and how each was resolved. Problems are numbered P1-P40 for cross-reference with the
resolution table at the end.

### Session 1: "Why Are Agents Thinking About SQL?" (2026-02-27)

Starting point: a working swarm with Planner, Critic, Scout, Executor, and Analyzer agents. The Planner produced
research plans as step sequences; the Critic challenged structural issues; Executors ran queries.

**Problems identified by examining SwarmDefaultPrompts.java and DTOs:**

**P1 — Planner prompt dominated by query construction reference.** 40% of the Planner prompt was JSON query syntax (
expression types, operators, selectors). This signaled to the LLM that query construction was the primary concern. The
Planner acted as a "query plan generator" rather than a research strategist.

**P2 — Critic challenge types were structural, not conceptual.** The PlanCritiqueDTO had: WRONG_DEPENDENCY,
INFEASIBLE_STEP, TOOL_MISMATCH. Out of 11 types, only 2-3 touched conceptual quality. Missing: FLAWED_HYPOTHESIS,
CONFOUNDED_ANALYSIS, CAUSAL_OVERCLAIM, SELECTION_BIAS, ECOLOGICAL_FALLACY. Without these enum values, the Critic
literally lacked vocabulary to express conceptual concerns.

**P3 — Examples reinforced structure over substance.** The Planner's "Customer Churn Analysis Plan" example read like a
SQL tutorial: "Use analyzeExpression on purchase_frequency, then executeQuery with QueryDTO grouping customers by
segment." Compare: "Investigate whether purchase frequency decline precedes churn — test the engagement decay
hypothesis."

**P4 — Step granularity calibrated to tool operations, not research milestones.** "Calculate churn rate by customer
segment" is a mechanical operation. A research milestone: "Determine whether customer value tier is a confounding
variable in the support-churn relationship."

**P5 — Success criteria too weak.** "What is the overall churn rate?" is a descriptive statistic. A genuine success
criterion: "Can we identify 2-3 actionable churn drivers with evidence distinguishing correlation from likely
causation?"

**P6 — No instruction to think about WHY.** Planner methodology was entirely structural: decompose, parallelize,
sequence, define outputs. No mention of hypothesis formulation, confound identification, or causal reasoning.

**Immediate fixes applied:** Moved query_structure out of Planner/Critic. Added hypothesis formulation phase. Expanded
challenge types with conceptual categories. Rewrote examples to show analytical reasoning. Added Research Design
methodology section.

**Core principle established:** Separate "what to investigate and why" (high-level agents) from "how to query it" (
executors).

### Session 2: Theoretical Framework (2026-02-28, early)

Moved from prompt fixes to fundamental architecture questions. The single Planner + Critic structure was challenged.

**P7 — LLM hypothesis diversity is surface variation.** "Support quality drives churn" vs "response time drives churn"
vs "ticket resolution drives churn" — all the same hypothesis about support. Same model with same inputs produces same
RLHF-favored outputs regardless of diversity instructions. Literature: Liang et al. (2023) on LLM output homogeneity.

**P8 — Affirmative bias in both generators and critics.** LLMs trained via RLHF are biased toward agreement and
confirmation. A critic prompted to "evaluate this plan" gravitates toward approval. Literature: Perez et al. (2022) on
sycophancy in RLHF models.

**P9 — No falsification criteria.** Only success criteria existed. Without explicit falsification conditions, hypotheses
couldn't be mechanically disproven — every result could be interpreted as partial support.

**P10 — Single planner creates cognitive bottleneck.** One planner, one perspective, one set of RLHF-favored
assumptions. No structural mechanism for genuine intellectual diversity.

**P11 — Critic just confirms planner's approach.** With structural challenge types and affirmative bias, the Critic
became a rubber-stamp. Challenging the plan's conceptual foundation required adversarial incentive the architecture
didn't provide.

**P12 — No mechanism to prevent "agreeable enumeration."** LLMs default to listing options that sound different but
converge on the same underlying assumption. Literature: noted as consistent with RLHF reward hacking (Amodei et al.,
2016).

**P13 — Adversarial environments needed for genuine challenge.** Question raised: can ML adversarial training concepts (
GANs, adversarial examples) apply to LLM research agents? Conclusion: not directly, but the principle of opposed
optimization objectives applies. Literature: Goodfellow et al. (2014) on GANs, Irving et al. (2018) on AI safety via
debate.

**P14 — Schema-path-based perspectives impractical.** Early idea: anchor different generators to different schema
paths (orders→products vs customers→support). Problems: entity graphs have cycles, most relations are irrelevant,
produces mechanical variation not intellectual diversity.

**Resolution: Entity cluster anchoring.** Different generators receive different entity clusters from the schema,
producing genuinely different information ordering without forcing arbitrary path constraints.

**P15 — Forward-looking questions still need backward-looking evidence.** "How to reduce churn?" seems prescriptive, but
the evidence is historical. Even prognosis requires examining what ALREADY HAPPENED. Simulation/projection is
interpretation on top of historical analysis, not a separate evidence type.

**P16 — Unsupervised generators lack adversarial constraint.** With multiple generators replacing the single planner,
who ensures hypothesis quality? Generators have no incentive to be rigorous — they're optimized to produce
plausible-sounding output.

**P17 — Quality oversight comes too late.** An overseer reviewing all hypotheses after generation spots issues but can't
efficiently fix them. If it finds H1 and H3 are flawed, it writes about the quality of assumptions — but can't re-run
generation.

**Resolution: Refiners that measure, not judge.** Instead of "is this hypothesis good?" (invites affirmative bias),
refiners estimate specificity level, grounding level, scope coverage as continuous metrics. Mechanical thresholds
trigger re-generation with specific feedback. Literature: Mayo (2018) "Statistical Inference as Severe Testing" on
measurement vs judgment in scientific evaluation.

**Key design decisions from this session:**

- Multiple generators anchored to entity clusters (addressing P7, P10, P14)
- Inverted skeptical prompting: "PROVE this is confounded" exploits affirmative bias as rigor engine (addressing P8,
  P11)
- Continuous disagreement magnitudes instead of discrete verdicts (addressing P9)
- Falsification criteria mechanically derivable via negation (addressing P9)
- Separate Domain Researcher for external knowledge (addressing P16)

### Session 3: Predicate Grammar & Simulation (2026-02-28, mid-morning)

Formalized the evaluation framework and confronted the simulation question.

**P18 — Need formal evaluation language LLMs can produce reliably.** Free-text evaluation is unjudgeable. Needed:
structured claims that can be mechanically evaluated against data. Decision: JSON AST rather than custom grammar, since
LLMs produce well-formed JSON from training data. Literature: structured output research showing JSON reliability over
novel formats.

**P19 — "Give up" mechanism for skeptics.** When should a skeptic stop looking for problems? Pure LLM judgment on "
enough evidence" is unreliable. Literature: Mayo (2018) on severe testing — a test is severe only if it had a high
probability of detecting the flaw if the flaw existed. Budget exhaustion with no evidence found = high-confidence
clearance.

**P20 — Three fixed skeptic types too narrow.** The initial Artifact/Confound/Reverse-Causation split was a selective
simplification. Different hypotheses face different threats: temporal trends need maturation checks, cross-sectional
correlations need reverse causation. Literature: Shadish, Cook & Campbell (2002) "Experimental and Quasi-Experimental
Designs for Generalized Causal Inference" — full taxonomy of 37 internal validity threats across 4 categories.

**Resolution: Dynamic threat assignment from Campbell taxonomy.** Per hypothesis, map structural characteristics to
applicable threats. Each threat becomes one skeptic's mandate.

**P21 — Simulation circularity.** If regression learns Y = f(X1, X2, X3) and simulation asks "what if X3 changes?" —
it's querying the same model that defined the relationship. Zero new evidence for causality. The simulation output is a
restatement of the regression coefficient.

**P22 — Inter-variable dependencies in simulation.** Shifting X3 while holding X1, X2 constant assumes independence. In
reality, X3 change may cause X1 change. Proper simulation requires a full structural causal model — dramatically harder
than single-target regression. Literature: Pearl (2009) "Causality" — the distinction between P(Y|do(X)) and P(Y|X).

**P23 — TDD principle for skeptical checks.** If skeptics see the execution approach before generating checks, they
unconsciously design checks the implementation can pass. Checks should test the CLAIM, not the IMPLEMENTATION.
Literature: analogy to Beck (2003) "Test-Driven Development" — write tests before code.

**Resolution: Two-pass skeptic structure.** Pass 1 (blind to proof path): generate concerns about hypothesis. Pass 2 (
informed by proof path): execute checks with full context. What to check decided blind; how to check informed by
implementation.

**Design artifacts from this session:**

- Full JSON AST predicate grammar (comparison operators, quantifiers, GIVEN conditional, arithmetic)
- De Morgan's law-based null condition derivation
- Campbell validity taxonomy distributed across pipeline stages
- Construct validity → generator (Phase 2)
- Internal validity → skeptics (Phase 2 Stage 1 + Phase 4 Stage 2)
- Statistical conclusion → mechanical (Phase 5)
- External validity → web search (Phase 6)

### Session 4: Skeptic Execution & Factor Discovery (2026-02-28, afternoon)

Deep dive into execution mechanics, factor discovery, and the pre-hypothesis phase.

**P24 — Simulation inter-variable dependencies unresolved.** Even with constraints (range validation, co-movement
tracking), simulation on single-target regression can't model system dynamics. Literature: Pearl's
association-intervention boundary — P(Y|X=x) ≠ P(Y|do(X=x)).

**P25 — Skeptic templates not generalizable.** Tried to create reusable skeptic predicate patterns per threat type.
Artifact checks are semi-mechanizable (null sensitivity, outlier sensitivity). Confound stratification follows a
template. But reverse causation, survivorship, Simpson's paradox require domain-specific reasoning that resists
templating.

**P26 — Need two-phase skeptics: blind assessment + informed execution.** Formalized the TDD insight. Pass 1 generates
concerns without proof path (cheap, parallel). Pass 2 executes with full context (expensive, sequential). Separation
prevents checks from being designed to pass.

**P27 — Skeptic verbal conclusions vs measured corrections.** "Your methodology is flawed" without showing different
results is empty assertion. Solution: require skeptics to re-execute with corrections and demonstrate measured delta.
The delta IS the evidence. Natural credibility hierarchy: measured correction > partial correction with assumptions >
comment > ungrounded claim.

**P28 — Correction loop convergence.** When skeptics produce corrections, re-run, measure delta. But when to stop?
Solution: deterministic termination. Continue while delta > ε and positive. Stop when delta ≤ 0 (correction degraded) or
delta < ε (diminishing returns). No LLM judges when to stop.

**P29 — Non-causal questions don't fit the framework.** Descriptive ("what segments exist?"), anomaly detection ("what's
unusual?"), forecasting ("what will sales be?"), association ("what's bought together?") — estimated 30-40% of real
analytical questions. Forcing these through causal machinery produces sophisticated-looking but inappropriate analysis.

**Resolution: Scope limitation.** Architecture purpose-built for "why does X happen and what can we do about it?"
Descriptive questions routed to fundamentally simpler pipeline.

**P30 — Feature discovery needs empirical grounding.** LLM generators guessing which factors matter produces hypotheses
grounded in pretraining biases. Solution: regression-based factor ranking BEFORE hypothesis generation. Literature:
Tukey (1977) "Exploratory Data Analysis" — explore then hypothesize, not hypothesize then explore. Stability selection (
Meinshausen & Bühlmann, 2010) for robust feature importance across bootstrap samples. Model class reliance (Fisher,
Rudin & Dominici, 2019) for importance across model types.

**P31 — Feature set overlap between generators.** Multiple generators proposing features independently may converge on
the same variables. Solution: mechanical overlap check (>75% similarity triggers retry with feedback). If overlap
persists after 2 retries, escalate — data may genuinely have limited relevant factors. In that case, reduce generator
count rather than force artificial diversity.

**Design artifacts from this session:**

- Two-pass skeptic structure with correction-based evaluation
- Deterministic correction loop with mechanical termination
- Phase 2 factor discovery (stability selection + model class reliance)
- Feature proposal → overlap check → retry mechanism
- Credibility hierarchy for skeptic output

### Session 5: Causal Tooling & Virtuous Collapse (2026-02-28, evening — current)

The most transformative session. Quasi-experimental verification, causal inference tooling, and the predicate framework
removal.

**P32 — Quasi-experimental verification needed for prescriptive claims.** Without it, everything is circumstantial.
Confound skeptics eliminate specific confounds, not all possible ones. Rosenbaum bounds quantify hypothetical confound
strength, not actual existence. Backdoor criterion validates against assumed graph, not ground truth.

**Resolution: Quasi-experimental verification as primary causal evidence.** Find natural episodes where factors actually
changed, test whether hypothesis predicts the outcome. Closest observational data gets to experiment. Literature:
Campbell & Stanley (1963) "Experimental and Quasi-Experimental Designs for Research."

**P33 — Advocates cherry-pick external evidence.** Advocates with web search find confirming sources regardless of
truth. An advocate searching "is churn seasonal in SaaS" will find confirming sources.

**Resolution: External validity moved to neutral Domain Researcher (second pass, no hypothesis allegiance). Advocates
stripped of web search.**

**P34 — Meta-Critic challenges Judge too late.** By the time a meta-Critic reviews the Judge's reconciliation, biased
advocacy has already shaped reasoning.

**Resolution: Per-hypothesis Prosecutor with opposed incentives. Both Advocate and Prosecutor receive identical
read-only evidence. Judge receives balanced adversarial input per hypothesis.**

**P35 — Simulation is circular for prescriptive analysis.** Full recognition of P21/P22/P24. Simulation consuming
regression models cannot provide new evidence for causation.

**P36 — Custom predicate evaluation redundant with ML tools.** CausalImpact (Brodersen et al., 2015) does Bayesian
structural time series with counterfactual projection — exactly what multivariate ITS was being designed to do. DoWhy (
Sharma & Kiciman, 2020) provides unified causal inference with multiple identification strategies and built-in
refutation tests (placebo, random common cause, data subset) — exactly what skeptic templates were being designed to do.

**Resolution: Delegate evaluation to established tools. CausalImpact for temporal interventions, DoWhy for
cross-sectional designs. The system's job becomes: detect which quasi-experimental structure exists → select method →
run → evaluate output.**

**P37 — Skeptics operating at wrong level.** With causal tools handling evaluation, skeptics challenging SQL queries and
predicate computations are challenging the wrong thing. The causal conclusion depends on MODEL SPECIFICATION: right
control variables, right graph edges, right preprocessing.

**Resolution: Skeptics challenge model specification post-ML. Corrections re-run the causal model with modified
specifications. Delta in causal effect estimates is the objective evidence of skeptic contribution.**

**P38 — Executor complexity disproportionate to contribution.** Executor started as "opinionless doer" translating
predicates to SQL. Through design iteration, became ML pipeline orchestrator: feature derivation → preprocessing →
causal model. This is actually simpler and more powerful than the predicate-evaluation approach.

**Key insight: "Executors went from peak of complexity to virtuous collapse." The predicate framework was solving "how
to evaluate" when the real value is upstream (what to investigate) and downstream (what it means). Offloading to ML
tools simplified the architecture AND improved research quality.**

**P39 — Permanently co-moving factors.** If two candidate causes always move together, no statistical method can
distinguish which is causal.

**Resolution: Run causal model first. CausalImpact/DoWhy handle multicollinearity internally (cross-sectional variation,
instrumental variables, partial identification). If the model resolves it — done. If not — report as "causal bundle"with
honest limitation.**

**P40 — Structural break detection not the strongest quasi-experimental method.** Several stronger methods exist
depending on data structure: Difference-in-Differences when natural treatment/control groups exist, Regression
Discontinuity when threshold-based treatment exists, Synthetic Control Method for constructing counterfactuals.
Literature: Abadie, Diamond & Hainmueller (2010) on synthetic control, Imbens & Lemieux (2008) on RDD.

**Resolution: Don't pick one method. Detect which quasi-experimental structures exist in data, apply the appropriate
method. Structural break detection is the fallback, not the primary approach.**

### Session 6: Multi-Entity Case Study Validation (2026-03-01)

Stress-tested the architecture against four complex hierarchical case studies with 4-7 entity levels each, cross-entity
causal arcs, and 23 planted validity traps. The case studies (river basin water quality, power grid reliability, urban
logistics, pharmaceutical cold chain) exposed structural gaps in the design.

**PCS-1 — Cross-entity causal graphs not expressible.** Hypothesis spec had flat edges. Real causal paths cross entity
levels: `parcel.fertilizer_rate → soil.no3 → stream.no3 → reservoir.no3`. DoWhy takes flat DAG. The join strategy for
flattening multi-entity paths is itself a specification choice skeptics need to challenge.

**PCS-2 — Threshold nonlinearities invisible to causal tools.** CausalImpact/DoWhy assume smooth functional forms. Case
4 insulation age: linear degradation for 30 months, then sharp cliff. LGBM captures this; causal tools miss it entirely.

**PCS-3 — Temporal scale mismatch inverts effects.** Relationship direction changes across timescales. Case 2:
substations near generation have low individual-event impact (short-run inhibitor) but degrade from maintenance neglect
until cascade (long-run amplifier).

**PCS-4 — Measurement entity as hidden confounder.** Case 1: monitoring stations deployed year 4 on problem segments —
network appears worse because bad segments became visible, not because they worsened. Case 4: logger calibration drift
creates +0.4°C bias correlated with season.

**PCS-5 — Mediator chain discovery not guaranteed.** Case 3:
`congestion → schedule_deviation → fatigue → failed_attempt`. If generator proposes direct`congestion → failed_attempt`,
total effect is correct but mechanism is wrong → wrong intervention.

**PCS-6 — Hidden moderators / subgroup heterogeneity.** Case 4: excursion rate identical across routes, but consequence
rate depends entirely on SKU stability class. Aggregate analysis masks the mechanism.

**PCS-7 — Ecological fallacy at entity level.** Case 1: high-intensity agriculture sub-catchments show LOWER stream
nitrate (flat terrain → denitrification in slow runoff). Case 2: zones with older infrastructure have LOWER outage
duration (redundant mesh topology is a hidden proxy).

**PCS-8 — Treatment diffusion (spillover).** Case 2: corridor under maintenance reroutes load to neighbors, elevating
their fault rates — if neighbors are "control," treated corridor looks better by contaminated comparison. Undetectable
from data alone.

**PCS-9 — Regression to mean.** Case 2: maintenance prioritizes assets with recent alarms. Post-maintenance alarm
reduction ~40% overestimated because alarm bursts are partly transient. Undetectable from data alone.

**PCS-10 — Hawthorne / measurement gaming.** Case 3: driver dashboard improves schedule adherence but changes failure
code composition — drivers skip difficult deliveries, marking "address error." Compositional shift detectable; causal
attribution not.

**PCS-11 — Range restriction / collinearity.** Case 3: senior drivers always assigned familiar zones. Nearly no
observations of senior+unfamiliar or junior+familiar. Familiarity effect real but magnitude inflated.

**PCS-12 — Omitted entity layer amplifying downstream.** Case 3: one merchant (28% volume) uses non-standard packaging →
longer stops → schedule compression → elevated failure for ALL subsequent parcels on the route. The entity exists in
schema but its sequential amplification effect requires feature engineering the pipeline doesn't automatically generate.

**Resolutions organized into three Design Decisions:**

**DD1 — Cross-Entity Path Discovery** (addresses PCS-1, PCS-7, PCS-12): Mechanical BFS path enumeration on FK topology +
DP-constrained attribute association screening. Multi-level aggregation sensitivity catches ecological fallacy.
Generators receive empirically-supported path menu rather than free invention.

**DD2 — Three-Tier Detection for Execution Blind Spots** (addresses PCS-2, PCS-3, PCS-4): Nonlinearity scan (SHAP +
segmented regression) catches threshold effects mechanically. Multi-timescale sensitivity grid catches temporal
inversions. Residual diagnostics detect unexplained patterns, escalating through targeted Domain Researcher search to
specific user inquiry.

**DD3 — Mandatory Heterogeneity + Accepted Limitations** (addresses PCS-6, PCS-8, PCS-9, PCS-10, PCS-11): Heterogeneity
check via residual clustering + SHAP interaction is mandatory and mechanical. Range restriction reported via VIF/overlap
checks. Treatment spillover, regression to mean, and measurement gaming documented as explicit assumptions —
epistemological limits, not gaps to fix.

**Validation results**: 14/23 traps caught mechanically, 9/23 partially caught (3 accepted epistemological limits, 3
domain knowledge gaps, 3 feature engineering sophistication), 0/23 missed entirely. The 3 feature engineering cases (
cascade topology, sequential amplification, survivorship selection) represent the remaining architectural boundary — the
system finds right entities and associations but misses structural relationships WITHIN entities (ordering, adjacency,
selection).

### Session 7: Live Case Study Execution & Generator Redesign (2026-03-04)

Ran the Case 4 pharmaceutical cold chain benchmark against a live stability selection service. Executed the full
generator loop manually, discovering fundamental architectural problems.

**P41 — Near-outcome proxy screens all other features.** First stability run: `pre_departure_temp_c` ranked #1 across
all 4 model families with importance 7.6× the next feature. It's a near-outcome mediator — warm container at dispatch →
excursion is almost tautological. Everything downstream became noise. Architecture had no mechanism to detect and handle
this.

**Resolution: Iterative target decomposition.** When a feature dominates AND correlates with uncontrollable factors,
flip it to become the new target. Peel back one causal layer:
`excursion ← departure_temp ← f(ambient, node_health, container_age)`. Strip uncontrollables at each layer. Repeat until
actionable variables surface.

**P42 — One-shot stability selection is not hypothesis generation.** The original Phase 2 (one-shot regression) → Phase
3 (generators interpret) separation was artificial. Interpreting "this is a mediator" and deciding to flip the target IS
causal reasoning. The strip/flip decisions ARE the hypothesis. There is no separate generation step after stability
selection — the iterative loop IS generation.

**Resolution: Merge Phase 2+3 into iterative generator loop.** Each generator runs its own chain of stability
selections, making causal decisions at each step. The hypothesis emerges from the sequence of runs and decisions, not
from LLM graph proposal after a one-shot regression.

**P43 — Entity clusters don't constrain causal structure.** Generators anchored on "entity clusters" still wrote
kitchen-sink queries pulling everything reachable from the fact table. No causal commitment was forced by the cluster
assignment.

**Resolution: Anchor on non-target entities.** Generator seeded with `node_ops_logs` writes SQL from node operations →
shipments, naturally discovering "before the trip" causes. Generator seeded with `receiving_logs` discovers "after the
trip" causes. The join topology IS the causal commitment. Target entity (`shipments`) must never be the anchor.

**P44 — Skeptics only challenged executor, not generator.** The generator's strip/flip decisions are the
highest-leverage causal commitments in the pipeline. A bad strip removes a variable from all downstream analysis
permanently. Original architecture had no adversarial pressure on these decisions.

**Resolution: Two-stage skeptics.** Stage 1 (post-generation) challenges strip/flip decisions by running
counter-stability analyses with corrected feature sets. Stage 2 (post-execution) challenges quasi-experimental
specification, as before. Both require empirical evidence.

**P45 — 1:N join fan-out silently corrupts results.** Joining `shipments` to `node_ops_logs` (multiple logs per node per
day) inflated 562k rows to 720k without any visible error. Models overweighted heavily-monitored nodes.

**Resolution: Mandatory row count verification.** After any JOIN, verify output row count matches target entity grain.
If inflated, aggregate N-side table to correct grain before joining. Added to generator system prompt as data
engineering guard.

**P46 — God's-eye columns in output data bypass analytical traps.** Fields like `max_temp_actual_c`, `logger_bias_c`,
`logger_has_fault`, `container_wall_U`, `driver_care_factor` exposed simulation internals. An agent could read logger
drift from a boolean instead of discovering it from seasonal patterns.

**Resolution: Strip intrinsic columns from all output tables.** Any field exposing information a real-world analyst
wouldn't have access to must be removed from agent-visible data. Logger drift must be discovered from calibration date
patterns, not labeled.

**P47 — SHAP dependence reveals different signals at different causal layers.** `container_age_months` showed massive
nonlinearity signal (tree rank 1, linear rank 17) when predicting `pre_departure_temp_c`. But the SHAP curve
oscillated — no clean threshold. Because container age affects transit insulation, not storage temperature. The variable
was at the right causal layer for excursion prediction but the wrong layer for departure temp prediction.

**Resolution: Multi-generator coverage by design.** Each generator anchored on different entity naturally finds
variables at the appropriate causal layer. The "before the trip" generator concludes with node operations. The "during
the trip" generator (different anchor) finds container age at the correct causal layer. No single generator needs to
find everything.

### Session 8: Estimation Stack Upgrade (2026-03-06)

External review suggested replacing CausalImpact/DoWhy binary with a more systematic estimation architecture.
Assessment: partially absorb, don't replace.

**P48 — Phase 2 models discarded at causal estimation boundary.** LGBM/ensemble models built for factor discovery were
not reused for causal estimation. DML's first stage (nuisance parameter estimation) can directly reuse these models —
they serve double duty as flexible nuisance function estimators for treatment and outcome equations.

**Resolution: DML as universal estimation backbone.** Phase 2 models carry forward into Phase 4 as DML first-stage
estimators via cross-fitting. DML always runs regardless of whether a stronger quasi-experimental design is available,
providing a universal comparison point across all hypotheses. Literature: Chernozhukov et al. (2018).

**P49 — Identification strategy selection was conceptually resolved (P40) but not operationalized.** The architecture
said "detect which quasi-experimental structures exist" but didn't specify how.

**Resolution: Formalized identification strategy scanner as Phase 4 pre-step.** Mechanical pattern matching: threshold
assignment → RDD, instrument candidates from Phase 2.4 path discovery → IV, panel structure with staggered adoption →
DiD/SDiD, single treated unit time series → CausalImpact. Multiple strategies run in parallel when applicable.
Literature: Imbens & Lemieux (2008), Arkhangelsky et al. (2021).

**P50 — Heterogeneity estimation via cluster-then-estimate was unprincipled.** Phase 2.5 detected subpopulations but the
downstream response (split population, run separate models per cluster) required arbitrary cluster boundaries.

**Resolution: GRF replaces cluster-then-estimate.** Generalized Random Forests (Athey, Tibshirani & Wager 2019) estimate
conditional average treatment effects directly as a continuous function of covariates. Phase 2.5 still detects
heterogeneity and flags it; Phase 4 now estimates it via GRF with valid confidence intervals for individual-level
effects.

**P51 — Sensitivity analysis lacked E-values.** Rosenbaum bounds apply to matched/stratified designs. E-values (
VanderWeele & Ding 2017) provide more interpretable sensitivity on the risk ratio scale for general estimates — the
minimum confound strength needed to explain away the observed effect.

**Resolution: E-values added alongside Rosenbaum bounds in sensitivity suite.** Both reported; they complement rather
than replace each other.

**Not adopted: Causal discovery (PC/FCI) as executor pre-check.** PC algorithm's edge orientations are fragile under
faithfulness violations common in observational data. The architecture already has specification skeptics who test DAG
sensitivity by re-running with alternative graph structures — this is more informative than a single fragile graph.
Exception: FCI for detecting latent confounders could serve as prosecutor ammunition, but deferred as
implementation-level decision.

**P52 — Cross-hypothesis interactions structurally invisible.** Entity anchoring deliberately gives generators
non-overlapping feature sets. Generator A's GRF never sees Generator B's treatment variable as a covariate. Interactions
between hypotheses (amplifying, inhibiting, sign-flipping) cannot be discovered by any individual estimation. The
judge's verbal synthesis ("both matter") cannot quantify interaction structure.

**Resolution: Phase 4.5 Hypothesis Composition.** After individual hypotheses are validated, dedicated cross-hypothesis
GRF runs screen for pairwise interactions. Each surviving hypothesis's treatment is re-estimated with all other
hypotheses' treatment variables as covariates. Interacting pairs get multi-treatment DML (joint partial effects) and GRF
interaction surface estimation (functional form: additive/amplifying/inhibiting/sign-flip/threshold). Independent pairs
are reported as additive without joint estimation.

**P53 — No mediation decomposition between hypotheses.** Individual hypotheses estimate total effects. But intervention
planning requires knowing how effects decompose: "if I fix receiving but can't replace containers, how much of the
container age effect disappears?" This requires formal mediation analysis in multi-treatment settings.

**Resolution: [TODO] Mediation analysis framework.** Requires formal design. Core challenges: intermediate confounding,
sequential vs parallel mediation, heterogeneous mediation. Candidate approaches: interventional direct/indirect
effects (VanderWeele 2015), causal mediation with DML (Chernozhukov et al. 2024). Filed as open conceptual question
requiring dedicated design work.

**P54 — Joint DAG merge not specified.** Individual hypotheses have separate DAGs. Composition requires a joint DAG, but
merging may surface contradictions (hypothesis A says X→Y, hypothesis B says Y→X) and missing cross-hypothesis edges.

**Resolution: [TODO] DAG merge framework.** Requires formal design. Mechanical consistency checking is straightforward.
Cross-hypothesis edge discovery can reuse Phase 2.4 association screening. Conflict arbitration needs specification —
mechanical (estimate both directions, compare) vs judge-mediated.

### Session 9: End-to-End Notebook Verification (2026-03-11)

First complete run of the single-hypothesis causal verification pipeline against 540k cold chain shipments. Exposed
multiple design gaps through concrete execution.

**P55 — DAG refinement was conceptual, not operational.** D-separation testing required concrete API (
nx.is_d_separator), adaptive significance thresholding (p-values meaningless at 540k rows), and a structural resolution
pass before fallback edges.

**Resolution: Phase 3.3 formalized.** Three-step process: broad DAG → d-sep test with adaptive threshold (
max_abs_r^1.5) → agent proposes common cause nodes from schema → re-test → fallback edges flagged for skeptic. Applied:
vehicle_generation surfaced as common cause of reefer_kw and container_age; 6 fallback edges added for unresolved
violations.

**P56 — Fallback edges are unexplained band-aids.** D-sep violations resolved by direct edges (no mechanistic
explanation) should be treated differently from structurally resolved edges. Skeptics need to investigate whether the
edge represents a real causal path, a missing common cause, or a collider.

**Resolution: Fallback edge flagging.** Fallback edges carry metadata (correlation, attempted resolution,
investigation_needed flag). Passed to Stage 2 skeptic as priority investigation targets. Threat table updated with
fallback edge row.

**P57 — Controlling for legitimate confounder can absorb real treatment thresholds.** vehicle_generation is genuinely
exogenous (calendar-scheduled fleet transitions), but controlling for it collapses the within-generation age range,
absorbing the 30-month degradation threshold. Neither confounder-vs-collider diagnosis nor temporal precedence testing
detects this — it's range restriction induced by correct confound control.

**Status: RESOLVED via PipelineSpec sensitivity.confounder_drops with per-variable deviation thresholds.** Specification
sensitivity detects THAT dropping vehicle_generation changes the estimate, but can't distinguish "wrong to control"
from "right to control but need stratified estimation." Proposed: when dropping a confounder shifts estimate >X%, run
stratified estimation by that confounder × treatment quantiles.

**P58 — DoWhy v0.14 CI extraction broken for EconML DML.** get_confidence_intervals() returns None. Bootstrap inference
parameter goes in fit_params not init_params.

**Resolution: Access EconML object directly via estimate.params['_estimator_object'].effect_interval(). Documented as
DoWhy wrapper limitation.**

**P59 — Mediation decomposition requires DAG surgery that risks cycles.** Adding treatment→mediator edge for direct
effect estimation can create cycles with fallback edges.

**Resolution: Use EconML directly for mediation runs, bypassing DoWhy DAG.** Confounder sets derived mechanically (
backdoor set + mediator). DoWhy handles identification + total effect + refutations; EconML direct handles
decomposition. Mediation is conditional (triggered by independently_actionable_mediators field) and labeled
INFORMATIONAL.

**P60 — Auto-correction loop controlled for mediators and post-treatment variables.** Residual correlation with
pre_departure_temp_c (r=0.34) triggered correction that absorbed 92% of the effect — because pre_departure_temp IS the
mechanism.

**Resolution: Filter candidates by DAG structure.** Exclude descendants of treatment and outcome (nx.descendants).
Exclude fields not in DAG (unknown causal status). Only DAG-internal, non-descendant, non-used fields eligible for
auto-correction. Unknown fields reported for skeptic review.

**P61 — Spec sensitivity mixed continuous and binary treatment units.** pp/month (continuous) compared against pp/jump (
binary threshold) produced 2494% deviation — meaningless.

**Status: RESOLVED.** PipelineSpec estimation_variants separate continuous and binary treatment forms;
sensitivity.model_variants cross-reference by variant_id. Implementation fix: compute max deviation within each
treatment-form group separately.

**P62 — Externalization test divergent on PIR allocation bias.** PIR foam showed 4× steeper age slope than VIP despite
being premium material. Hub allocation (20.8% PIR in Phoenix vs 5.2% VIP) explains the anomaly. God's-eye confirmed
identical degradation rates for all types — PIR slope is entirely confounding.

**Resolution: Externalization test correctly flagged. Pipeline cannot resolve mechanically from this data but identified
the right question.** Recommended: within-hub type comparisons or GRF with hub as additional X variable.

**Validation results from end-to-end run:** GRF discovered ambient temperature drives 76% of heterogeneity in age
effect — physically correct (heat gradient × degraded insulation). Age effect near-zero below 18°C, 0.08pp/month above
25.6°C. Prescriptive output: climate-indexed replacement policy, not uniform schedule. Blind LLM interpretation test
validated that Advocate role produces high-quality analysis from results alone; Prosecutor needs pipeline history to
catch DAG construction artifacts.

### Session 10: Executor Compiler Validation & Architectural Split (2026-03-23)

Six iterative compiler runs on H1 (containerInsulationType → excursionFlag) and one on H3 (nodeRefrigHealthPct →
excursionFlag) validated the PipelineSpec architecture and exposed systematic parameter drift.

**P63–P73** documented above in the resolution table. Key findings from iterative validation:

- PELT penalties drifted from 0.5 to 14.4 across runs for the same 120-month dataset until formula was codified (P64)
- D-sep noise floor ranged 0.013–0.055 depending on whether causally connected pairs were selected (P65)
- Bundled variables (containerWallAM2/doorAM2) entered W in 2 of 6 runs, biasing ATE toward zero (P66)
- Ecological fallacy dimension (nodeId) was replaced by coarser proxy (region) in 1 run, losing 47% of confounding
  control (P67)
- Temporal confounding at only one grain (dispatchMonth but not dispatchYear) in 5 of 6 runs (P68)
- Scoped variants written as prose notes the engine cannot execute (P69)

**P74 — Phase 2 model reuse architecturally orphaned.** P48 (Session 8) resolved that Phase 2 LGBM/ensemble models serve
as DML first-stage nuisance estimators. PipelineSpec has no mechanism to pass Phase 2 models to the Engine. The Engine
trains nuisance models from scratch.

**Status: DEFERRED.** Model reuse is a performance optimization, not a correctness issue. DML cross-fitting produces
valid inference regardless of nuisance model provenance. When Engine orchestration matures (Restate workflows with
artifact passing), Phase 2 model serialization can be added as a PipelineSpec extension (
`nuisance_model_artifacts: [path]`).

**P75 — DoWhy identification gate missing from PipelineSpec.** Original Phase 4 design had DoWhy verify backdoor
criterion before estimation, with REJECT → generator if identification fails. PipelineSpec has no identification abort
gate.

**Status: DEFERRED.** The Engine runs DoWhy identification as an informational step. The Compiler's DAG construction and
W matrix already encode the backdoor set — if the DAG is correct, identification succeeds by construction. Adding
`identification_gate: {abort_on_failure: bool}` is straightforward when needed.

**P76 — Phase 4/5 boundary blurred.** Original Phase 5 agents (Specification Sensitivity, Quasi-Experimental Check,
Robustness Checks, Residual Diagnostics, Range Restriction Check) collapsed into Engine execution steps configured by
PipelineSpec. Phase 5 no longer has independent agents.

**Status: ACCEPTED.** Phase 5 "agents" were always mechanical (no LLM). Making them Engine steps configured by
PipelineSpec is architecturally cleaner: all configuration in one place, all execution in one runner. The phase boundary
persists conceptually (evaluation ≠ estimation) but not as a separate orchestration step.

**P77 — Skeptic re-execution is full pipeline rerun.** Current design: skeptic proposes PipelineSpec corrections,
Compiler re-compiles, Engine re-runs from scratch. A correction to the W matrix only invalidates estimation and
downstream, not query or d-sep.

**Resolution: Persistent pipeline state with stage-level replay.** See Phase 4.2 revision.

**H3 validation results:** First-try pass on nodeRefrigHealthPct → excursionFlag. Correct mediator handling (
preDepartureTempC excluded from primary W, direct-effect variant created), severe positivity documented (3,889 treated
at 70% threshold), threshold variants at 50/60/70/80/90, discrepancy log caught generator tier mislabeling (generator
said 4,952 for "<70% health" but data shows that's <90%). PELT and structural max formulas applied correctly on first
attempt.

---

## Problem Resolution Table

| #      | Problem                                               | Session | Resolution                                                                                                   | Literature                                                             |
|--------|-------------------------------------------------------|---------|--------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------|
| P1     | Planner prompt 40% query syntax                       | 1       | Moved query_structure to Executor only                                                                       | —                                                                      |
| P2     | Critic challenge types structural only                | 1       | Added conceptual types (CONFOUNDED_ANALYSIS, etc.)                                                           | —                                                                      |
| P3     | Examples teach SQL not research                       | 1       | Rewrote examples with analytical reasoning                                                                   | —                                                                      |
| P4     | Step granularity = tool granularity                   | 1       | Research milestones replace tool operations                                                                  | —                                                                      |
| P5     | Success criteria too weak                             | 1       | Causal/explanatory criteria required                                                                         | —                                                                      |
| P6     | No instruction to think about WHY                     | 1       | Added hypothesis formulation phase                                                                           | —                                                                      |
| P7     | LLM diversity is surface variation                    | 2       | Entity cluster anchoring for genuine diversity                                                               | Liang et al. 2023                                                      |
| P8     | Affirmative bias in generators and critics            | 2       | Inverted skeptical prompting exploits bias as rigor                                                          | Perez et al. 2022                                                      |
| P9     | No falsification criteria                             | 2       | Mechanical negation via De Morgan's; later superseded by causal tool refutation                              | —                                                                      |
| P10    | Single planner cognitive bottleneck                   | 2       | Multiple generators with independent perspectives                                                            | —                                                                      |
| P11    | Critic rubber-stamps planner                          | 2       | Per-hypothesis Prosecutor with opposed incentives                                                            | Irving et al. 2018                                                     |
| P12    | Agreeable enumeration                                 | 2       | Structural discrimination requirement + overlap rejection                                                    | —                                                                      |
| P13    | Need adversarial environment                          | 2       | Opposed optimization objectives (Advocate + Prosecutor)                                                      | Goodfellow et al. 2014                                                 |
| P14    | Schema-path perspectives impractical                  | 2       | Entity cluster anchoring instead                                                                             | —                                                                      |
| P15    | Prescriptive questions need historical evidence       | 2       | All questions grounded in observed data; causal tools test mechanisms                                        | Pearl 2009                                                             |
| P16    | Unsupervised generators                               | 2       | Refiners measure quality metrics; mechanical thresholds trigger re-generation                                | Mayo 2018                                                              |
| P17    | Quality oversight too late                            | 2       | Refiners operate before execution, not after                                                                 | —                                                                      |
| P18    | Need formal evaluation language                       | 3       | JSON AST predicates; later superseded by hypothesis specification for causal tools                           | —                                                                      |
| P19    | Skeptic "give up" mechanism                           | 3       | Tool call budget; budget exhaustion with no evidence = clearance                                             | Mayo 2018                                                              |
| P20    | Three fixed skeptic types too narrow                  | 3       | Dynamic threat assignment from Campbell taxonomy                                                             | Shadish, Cook & Campbell 2002                                          |
| P21    | Simulation circularity                                | 3       | Replaced by quasi-experimental verification + CausalImpact/DoWhy                                             | —                                                                      |
| P22    | Inter-variable dependencies in simulation             | 3       | CausalImpact models full multivariate system; DoWhy handles graph-based identification                       | Pearl 2009                                                             |
| P23    | TDD principle for skeptical checks                    | 3       | Two-pass skeptic: blind concern generation, then informed execution                                          | Beck 2003 (analogy)                                                    |
| P24    | Simulation can't model system dynamics                | 4       | Replaced by established causal inference tooling                                                             | Brodersen et al. 2015                                                  |
| P25    | Skeptic templates not generalizable                   | 4       | Specification-level challenges (graph, controls, preprocessing) replace predicate templates                  | —                                                                      |
| P26    | Two-phase skeptic formalized                          | 4       | Pass 1: blind concerns. Pass 2: informed correction with measured delta                                      | —                                                                      |
| P27    | Verbal conclusions vs measured corrections            | 4       | Correction-based evaluation: re-run with modification, delta IS the evidence                                 | —                                                                      |
| P28    | Correction loop convergence                           | 4       | Deterministic: continue while delta > ε and positive                                                         | —                                                                      |
| P29    | Non-causal questions don't fit                        | 4       | Scope limited to causal/explanatory; descriptive routed separately                                           | —                                                                      |
| P30    | Feature discovery needs empirical grounding           | 4       | Phase 2: stability selection + model class reliance before hypothesis generation                             | Tukey 1977; Meinshausen & Bühlmann 2010; Fisher, Rudin & Dominici 2019 |
| P31    | Feature set overlap                                   | 4       | Mechanical overlap check, retry with feedback, escalate or reduce generators                                 | —                                                                      |
| P32    | Quasi-experimental verification mandatory             | 5       | Causal evidence tiers; Tier 2/3 get explicit limitation statements                                           | Campbell & Stanley 1963                                                |
| P33    | Advocates cherry-pick external evidence               | 5       | Web search moved to neutral Domain Researcher                                                                | —                                                                      |
| P34    | Meta-Critic too late                                  | 5       | Per-hypothesis Prosecutor replaces post-hoc meta-Critic                                                      | —                                                                      |
| P35    | Simulation circular for prescriptive                  | 5       | CausalImpact/DoWhy replace simulation entirely                                                               | Brodersen et al. 2015; Sharma & Kiciman 2020                           |
| P36    | Custom evaluation redundant with ML tools             | 5       | Predicate framework removed; evaluation delegated to CausalImpact/DoWhy                                      | —                                                                      |
| P37    | Skeptics at wrong level                               | 5       | Specification skeptics: challenge model inputs, not query results                                            | —                                                                      |
| P38    | Executor complexity vs contribution                   | 5       | Virtuous collapse: simpler ML pipeline orchestrator, better results                                          | —                                                                      |
| P39    | Permanently co-moving factors                         | 5       | Run causal model first; report as "causal bundle" if unresolvable                                            | —                                                                      |
| P40    | Structural break not strongest method                 | 5       | Method selection based on data structure; DiD, RDD, synthetic control when applicable                        | Abadie et al. 2010; Imbens & Lemieux 2008                              |
| PCS-1  | Cross-entity causal graphs not expressible            | 6       | DD1: BFS path enumeration + DP-constrained association screening                                             | Weinstein & Blei 2024; Sgouritsa et al. 2024                           |
| PCS-2  | Threshold nonlinearities invisible to causal tools    | 6       | DD2 Tier 1: SHAP dependence + segmented regression (Muggeo's method)                                         | Hansen 2000; Bai & Perron 1998; Lundberg & Lee 2017                    |
| PCS-3  | Temporal scale mismatch inverts effects               | 6       | DD2: Multi-timescale sensitivity grid in Phase 6.3                                                           | Runge et al. 2019                                                      |
| PCS-4  | Measurement entity as hidden confounder               | 6       | DD2: Tier 1 residual diagnostics + measurement metadata + Tier 2 targeted search                             | —                                                                      |
| PCS-5  | Mediator chain discovery not guaranteed               | 6       | DD1: BFS path enumeration surfaces mediator entities mechanically                                            | —                                                                      |
| PCS-6  | Hidden moderators / subgroup heterogeneity            | 6       | DD3: Mandatory residual clustering + SHAP interaction values                                                 | —                                                                      |
| PCS-7  | Ecological fallacy at entity level                    | 6       | DD1: Multi-level aggregation sensitivity catches effect inversions                                           | —                                                                      |
| PCS-8  | Treatment diffusion (spillover)                       | 6       | DD3: Accepted limitation, documented as explicit assumption                                                  | —                                                                      |
| PCS-9  | Regression to mean                                    | 6       | DD3: Accepted limitation, documented as explicit assumption                                                  | —                                                                      |
| PCS-10 | Hawthorne / measurement gaming                        | 6       | DD3: Accepted limitation, compositional shift partially detectable                                           | —                                                                      |
| PCS-11 | Range restriction / collinearity                      | 6       | DD3: VIF + variance + support overlap checks with reliability caveats                                        | —                                                                      |
| PCS-12 | Omitted entity layer amplifying downstream            | 6       | DD1: BFS traverses all reachable entities (partial — sequential effects need feature engineering)            | —                                                                      |
| P41    | Near-outcome proxy screens all features               | 7       | Iterative target decomposition: flip dominant mediator to new target, peel back causal layers                | —                                                                      |
| P42    | One-shot stability ≠ hypothesis generation            | 7       | Merge Phase 2+3: generator IS the iterative stability loop                                                   | Tukey 1977                                                             |
| P43    | Entity clusters don't constrain causal structure      | 7       | Anchor on non-target entities; join topology IS causal commitment                                            | —                                                                      |
| P44    | Skeptics only challenged executor                     | 7       | Two-stage skeptics: Stage 1 challenges generation (counter-stability analyses), Stage 2 challenges execution | —                                                                      |
| P45    | 1:N join fan-out silently corrupts                    | 7       | Mandatory row count verification after JOINs; aggregate N-side to correct grain                              | —                                                                      |
| P46    | God's-eye columns bypass traps                        | 7       | Strip intrinsic columns from all output tables                                                               | —                                                                      |
| P47    | SHAP signals differ across causal layers              | 7       | Multi-generator coverage: each anchor finds variables at appropriate causal layer                            | —                                                                      |
| P48    | Phase 2 models discarded at causal estimation         | 8       | DML reuses Phase 2 ensemble as first-stage nuisance estimators                                               | Chernozhukov et al. 2018                                               |
| P49    | Identification strategy selection not operationalized | 8       | Formalized identification scanner: RDD/IV/DiD/SDiD/CausalImpact pattern matching                             | Imbens & Lemieux 2008; Arkhangelsky et al. 2021                        |
| P50    | Cluster-then-estimate unprincipled for heterogeneity  | 8       | GRF estimates CATE directly with valid CIs, no arbitrary cluster boundaries                                  | Athey, Tibshirani & Wager 2019                                         |
| P51    | Sensitivity analysis lacked E-values                  | 8       | E-values added alongside Rosenbaum bounds                                                                    | VanderWeele & Ding 2017                                                |
| P52    | Cross-hypothesis interactions structurally invisible  | 8       | Phase 4.5: cross-hypothesis GRF screening + multi-treatment DML + interaction surfaces                       | Athey et al. 2019; Chernozhukov et al. 2018                            |
| P53    | No mediation decomposition between hypotheses         | 8       | [TODO] Formal mediation framework for multi-treatment settings                                               | VanderWeele 2015; Imai, Keele & Tingley 2010                           |
| P54    | Joint DAG merge not specified                         | 8       | [TODO] DAG consistency checking + cross-hypothesis edge discovery + conflict arbitration                     | Pearl 2009                                                             |
| P55    | DAG refinement not operational                        | 9       | Phase 3.3: d-sep test + adaptive threshold (max_r^1.5) + structural pass + fallback flagging                 | —                                                                      |
| P56    | Fallback edges unexplained                            | 9       | Flagged with metadata, passed to Stage 2 skeptic as priority targets                                         | —                                                                      |
| P57    | Confounder control absorbs real threshold             | 9       | OPEN: stratified estimation as diagnostic when dropping confounder shifts estimate >X%                       | —                                                                      |
| P58    | DoWhy v0.14 CI extraction broken                      | 9       | Access EconML directly via estimate.params['_estimator_object']                                              | —                                                                      |
| P59    | Mediation DAG surgery creates cycles                  | 9       | EconML direct for decomposition; DoWhy for identification + total + refutations                              | —                                                                      |
| P60    | Auto-correction controls for mediators                | 9       | Filter by nx.descendants + DAG membership; unknown fields to skeptic review                                  | —                                                                      |
| P61    | Spec sensitivity mixes treatment units                | 9       | OPEN: separate continuous and binary groups for deviation computation                                        | —                                                                      |
| P62    | Externalization divergent on allocation bias          | 9       | Correctly flagged; pipeline can't resolve mechanically, recommends within-hub comparison                     | —                                                                      |
| P63    | Executor ad hoc decisions unauditable                 | 10      | Compiler/Engine split: all decisions captured in PipelineSpec                                                | —                                                                      |
| P64    | PELT penalty drift across runs                        | 10      | Formula codified: sensitive=log(T), conservative=3×log(T)                                                    | Bai & Perron 1998                                                      |
| P65    | D-sep noise floor inflated by connected pairs         | 10      | Pair selection criteria: unrelated measurement domains only                                                  | —                                                                      |
| P66    | Bundled variables absorb treatment effect             | 10      | CO-DETERMINED failure mode: within-treatment-level variance ≈ 0 → exclude                                    | —                                                                      |
| P67    | Ecological fallacy dimension dropped from W           | 10      | PROXY ABSORPTION failure mode: finer variable mandatory; ecological fallacy dimension MUST be in W           | —                                                                      |
| P68    | Temporal confounding at single grain                  | 10      | Multi-grain temporal confounding: year + month + finer if system operates at that scale                      | —                                                                      |
| P69    | Scoped variants as prose notes                        | 10      | Filter DTO on estimation_variants: {column, operator, values}                                                | —                                                                      |
| P70    | No unmeasured confounding quantification              | 10      | unmeasured_confounding field: E_VALUE + ROSENBAUM_BOUNDS per variant                                         | VanderWeele & Ding 2017; Rosenbaum 2002                                |
| P71    | Post-treatment variables slip through                 | 10      | POST-TREATMENT failure mode with examples                                                                    | —                                                                      |
| P72    | Treatment structural max R² inconsistent              | 10      | Formula codified: categorical=1-(1/k), binary=4×p×(1-p)                                                      | —                                                                      |
| P73    | Table-grain variables undocumented                    | 10      | TABLE-GRAIN CONFUSION failure mode: document entity-constant variables and which entity ID absorbs them      | —                                                                      |
| P74    | Phase 2 model reuse orphaned                          | 10      | DEFERRED: Engine trains fresh nuisance models; model serialization deferred to orchestration maturity        | Chernozhukov et al. 2018                                               |
| P75    | DoWhy identification gate missing                     | 10      | DEFERRED: Engine runs DoWhy identification as informational step; hard abort deferred                        | Pearl 2009                                                             |
| P76    | Phase 4/5 boundary blurred                            | 10      | ACCEPTED: Phase 5 agents were always mechanical; collapse into Engine steps is cleaner                       | —                                                                      |
| P77    | Skeptic re-execution is full pipeline rerun           | 10      | Persistent pipeline state with stage-level replay; skeptic mutations replay from invalidation point          | —                                                                      |

### Complete Literature References

- Abadie, Diamond & Hainmueller (2010). "Synthetic Control Methods for Comparative Case Studies." *Journal of the
  American Statistical Association.*
- Amodei et al. (2016). "Concrete Problems in AI Safety." *arXiv:1606.06565.*
- Arkhangelsky et al. (2021). "Synthetic Difference-in-Differences." *American Economic Review.*
- Athey, Tibshirani & Wager (2019). "Generalized Random Forests." *Annals of Statistics.*
- Bai & Perron (1998). "Estimating and Testing Linear Models with Multiple Structural Changes." *Econometrica.*
- Beck (2003). *Test-Driven Development: By Example.* Addison-Wesley.
- Brodersen et al. (2015). "Inferring Causal Impact Using Bayesian Structural Time-Series Models." *Annals of Applied
  Statistics.*
- Campbell & Stanley (1963). *Experimental and Quasi-Experimental Designs for Research.* Houghton Mifflin.
- Chernozhukov et al. (2018). "Double/Debiased Machine Learning for Treatment and Structural Parameters." *The
  Econometrics Journal.*
- Fisher, Rudin & Dominici (2019). "All Models are Wrong, but Many are Useful: Learning a Variable's Importance by
  Studying an Entire Class of Prediction Models Simultaneously." *JMLR.*
- Goodfellow et al. (2014). "Generative Adversarial Nets." *NeurIPS.*
- Hansen (2000). "Sample Splitting and Threshold Estimation." *Econometrica.*
- Imbens & Lemieux (2008). "Regression Discontinuity Designs: A Guide to Practice." *Journal of Econometrics.*
- Imai, Keele & Tingley (2010). "A General Approach to Causal Mediation Analysis." *Psychological Methods.*
- Irving, Christiano & Amodei (2018). "AI Safety via Debate." *arXiv:1805.00899.*
- Jin et al. (2024). "Can Large Language Models Infer Causation from Correlation?" (Corr2Cause). *ICLR.*
- Liang et al. (2023). "Encouraging Divergent Thinking in Large Language Models." *ACL.*
- Lundberg & Lee (2017). "A Unified Approach to Interpreting Model Predictions." *NeurIPS.*
- Mayo (2018). *Statistical Inference as Severe Testing.* Cambridge University Press.
- Meinshausen & Bühlmann (2010). "Stability Selection." *Journal of the Royal Statistical Society.*
- Pearl (2009). *Causality: Models, Reasoning, and Inference.* 2nd ed. Cambridge University Press.
- Perez et al. (2022). "Discovering Language Model Behaviors with Model-Written Evaluations." *arXiv:2212.09251.*
- Rosenbaum (2002). *Observational Studies.* 2nd ed. Springer.
- Runge et al. (2019). "Detecting and Quantifying Causal Associations in Large Nonlinear Time Series Datasets." *Science
  Advances.* (PCMCI framework)
- Semenova, Rudin & Parr (2022). "On the Existence of Simpler Machine Learning Models." *AISTATS.*
- Sgouritsa et al. (2024). "PC-SubQ: Decomposing Causal Discovery into Pairwise Sub-questions." *arXiv.*
- Shadish, Cook & Campbell (2002). *Experimental and Quasi-Experimental Designs for Generalized Causal Inference.*
  Houghton Mifflin.
- Sharma & Kiciman (2020). "DoWhy: An End-to-End Library for Causal Inference." *arXiv:2011.04216.*
- Tukey (1977). *Exploratory Data Analysis.* Addison-Wesley.
- VanderWeele (2015). *Explanation in Causal Inference: Methods for Mediation and Interaction.* Oxford University Press.
- VanderWeele & Ding (2017). "Sensitivity Analysis in Observational Research: Introducing the E-Value." *Annals of
  Internal Medicine.*
- Weinstein & Blei (2024). "Hierarchical Causal Models." *arXiv.* (Extended SCMs with inner plates for nested data)