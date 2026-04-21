import logging
import networkx as nx
import numpy as np
import pandas as pd
from dto.causal_verification_request import (
    EstimationVariant, FilterOperator, TreatmentForm, VariantFilter,
)
from econml.dml import LinearDML
from lightgbm import LGBMClassifier, LGBMRegressor
from service.pipeline_dataframe import PipelineDataFrame
from typing import Any, Optional

from .memory_budget import LGBM_DEFAULTS

logger = logging.getLogger(__name__)


def trace(msg, *args):
    logger.info("[TRACE] " + msg, *args)


def parse_dag_edges(dag_str: str) -> list[tuple[str, str]]:
    edges = []
    for part in dag_str.replace("\n", ";").split(";"):
        part = part.strip()
        if "->" in part:
            src, dst = part.split("->", 1)
            edges.append((src.strip(), dst.strip()))
    return edges


def edges_to_nx(edges: list[tuple[str, str]]) -> nx.DiGraph:
    G = nx.DiGraph()
    G.add_edges_from(edges)
    return G


def apply_variant_filter(
        data: PipelineDataFrame, variant: EstimationVariant
) -> PipelineDataFrame:
    if variant.filter is None:
        return data
    mask = eval_filter(data.raw, variant.filter)
    return data.filter_mask(mask)


def eval_filter(df: pd.DataFrame, f: VariantFilter) -> "pd.Series[bool]":
    if f.and_filters is not None:
        mask = pd.Series(True, index=df.index)
        for sub in f.and_filters:
            mask = mask & eval_filter(df, sub)
        return mask
    if f.or_filters is not None:
        mask = pd.Series(False, index=df.index)
        for sub in f.or_filters:
            mask = mask | eval_filter(df, sub)
        return mask
    if f.not_filter is not None:
        return ~eval_filter(df, f.not_filter)
    col = f.column
    if col not in df.columns:
        return pd.Series(True, index=df.index)
    if f.operator == FilterOperator.IN:
        return df[col].isin(f.values)
    elif f.operator == FilterOperator.GT:
        return df[col] > f.values[0]
    elif f.operator == FilterOperator.LT:
        return df[col] < f.values[0]
    elif f.operator == FilterOperator.EQ:
        return df[col] == f.values[0]
    return pd.Series(True, index=df.index)


def run_dml_quick(data, treatment, outcome, dag: nx.DiGraph,
                  discrete: bool = False) -> Optional[float]:
    try:
        w_cols = sorted(
            ((set(dag.predecessors(treatment)) if dag.has_node(treatment) else set())
             | (set(dag.predecessors(outcome)) if dag.has_node(outcome) else set()))
            - {treatment, outcome}
        )
        w_cols = [c for c in w_cols if c in data.columns]
        if not w_cols:
            w_cols = [c for c in data.columns if c not in (treatment, outcome)]

        enc = data.encoded
        Y = enc[outcome].values
        T = enc[treatment].values
        W = enc[w_cols].values

        model_t = LGBMClassifier(**LGBM_DEFAULTS) if discrete else LGBMRegressor(**LGBM_DEFAULTS)
        dml = LinearDML(
            model_y=LGBMRegressor(**LGBM_DEFAULTS),
            model_t=model_t,
            discrete_treatment=discrete,
        )
        dml.fit(Y, T, W=W)
        return float(dml.effect().mean())
    except Exception as e:
        logger.warning(f"Quick DML failed: {e}")
        return None


def replace_dag_node(dag: nx.DiGraph, old_node: str, new_node: str) -> nx.DiGraph:
    mapping = {old_node: new_node}
    return nx.relabel_nodes(dag, mapping)


def infer_treatment_form(data: PipelineDataFrame, column: str) -> TreatmentForm:
    if column not in data.columns:
        return TreatmentForm.CATEGORICAL
    if column in data.cat_columns:
        return TreatmentForm.CATEGORICAL
    raw_col = data.raw[column]
    if pd.api.types.is_numeric_dtype(raw_col):
        n_unique = raw_col.nunique()
        if n_unique <= 2:
            return TreatmentForm.CATEGORICAL
        if n_unique > 20:
            return TreatmentForm.CONTINUOUS
    return TreatmentForm.CATEGORICAL


def build_protected_columns(
        spec, dag: nx.DiGraph
) -> set[str]:
    protected = set()
    original = spec.original_treatment or spec.treatment
    protected.add(original)

    mediator_cols = {m.column for m in spec.mediators_excluded}
    protected |= mediator_cols

    if dag.has_node(original):
        protected |= nx.ancestors(dag, original)

    return protected
