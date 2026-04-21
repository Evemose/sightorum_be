import logging
import networkx as nx
import numpy as np
from itertools import combinations
from scipy.stats import pearsonr
from sklearn.linear_model import LinearRegression

from .memory_budget import CI_ALPHA

logger = logging.getLogger(__name__)


def dsep_refinement(data, edges, threshold):
    all_nodes = list({n for e in edges for n in e})
    violations, confirmed = _run_dsep_tests(data, edges, all_nodes, threshold)
    refined_edges, fallback = _add_fallback_edges(edges, violations)
    return {
        "broad_edge_count": len(edges),
        "refined_edge_count": len(refined_edges),
        "violation_count": len(violations),
        "confirmed_count": len(confirmed),
        "fallback_edges": [list(e) for e in fallback],
        "refined_edges": refined_edges,
        "violations": [
            {"node_a": v["node_a"], "node_b": v["node_b"],
             "correlation": v["correlation"], "p_value": v["p_value"]}
            for v in violations
        ],
    }


def _run_dsep_tests(data, edges, all_nodes, threshold):
    implications = _get_dsep_implications(edges, all_nodes)
    buffer = {}
    for a, b, cond in implications:
        r = _test_ci(data, a, b, cond)
        buffer[(a, b, frozenset(cond))] = r
    max_r = max((abs(r["correlation"]) for r in buffer.values()), default=0)
    violations, confirmed = [], []
    for (a, b, _), r in buffer.items():
        if r["p_value"] < CI_ALPHA and abs(r["correlation"]) > max_r ** 1.5:
            violations.append(r)
        else:
            confirmed.append(r)
    return violations, confirmed


def _get_dsep_implications(edges, all_nodes):
    G = nx.DiGraph(edges)
    seen, unique = set(), []
    for a, b in combinations(all_nodes, 2):
        if G.has_edge(a, b) or G.has_edge(b, a):
            continue
        parents = set(G.predecessors(a)) | set(G.predecessors(b)) - {a, b}
        for cond in [parents, set()]:
            if nx.is_d_separator(G, {a}, {b}, cond):
                key = (min(a, b), max(a, b), tuple(sorted(cond)))
                if key not in seen:
                    seen.add(key)
                    unique.append((a, b, cond))
    return unique


def _test_ci(data, a, b, cond_set):
    enc = data.encoded
    a_vals = enc[a].values.astype(float)
    b_vals = enc[b].values.astype(float)
    if not cond_set:
        r, p = pearsonr(a_vals, b_vals)
    else:
        X = enc[list(cond_set)].values.astype(float)
        res_a = a_vals - LinearRegression().fit(X, a_vals).predict(X)
        res_b = b_vals - LinearRegression().fit(X, b_vals).predict(X)
        r, p = pearsonr(res_a, res_b)
    return {"node_a": a, "node_b": b, "cond_set": list(cond_set),
            "correlation": float(r), "p_value": float(p)}


def add_fallback_edges(current_edges, violations):
    added = []
    edges = list(current_edges)
    for v in violations:
        a, b = v["node_a"], v["node_b"]
        if (a, b) in edges or (b, a) in edges:
            continue
        G_temp = nx.DiGraph(edges)
        a_d = len(nx.ancestors(G_temp, a)) if a in G_temp else 0
        b_d = len(nx.ancestors(G_temp, b)) if b in G_temp else 0
        edge = (a, b) if a_d <= b_d else (b, a)
        G_test = nx.DiGraph(edges + [edge])
        if nx.is_directed_acyclic_graph(G_test):
            edges.append(edge)
            added.append(edge)
    return edges, added


_add_fallback_edges = add_fallback_edges
