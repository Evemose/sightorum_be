"""Parse and evaluate tier ordering notation for domain rankings.

Notation::

    (70) > (60, 80) > (50, 90)

Tiers are parenthesized groups separated by ``>``.  Elements within a
tier are asserted approximately equal.  Cross-tier pairs assert the
higher tier has a larger empirical effect than the lower tier.

The notation unwinds to all valid chains (one element per tier) and
checks every adjacent pair.  Within-tier pairs are checked for
approximate equality.
"""

from __future__ import annotations

import logging
import math
import re
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class TierOrdering:
    """Parsed tier notation."""
    tiers: list[list[str]]  # outer = tier (high→low severity), inner = elements


def parse_ordering(notation: str) -> TierOrdering:
    """Parse ``(70) > (60, 80) > (50, 90)`` into a TierOrdering."""
    notation = notation.strip()
    if not notation:
        raise ValueError("Empty ordering notation")

    tiers: list[list[str]] = []
    for tier_str in notation.split(">"):
        tier_str = tier_str.strip()
        # Strip outer parens if present
        if tier_str.startswith("(") and tier_str.endswith(")"):
            tier_str = tier_str[1:-1]
        elements = [e.strip() for e in tier_str.split(",") if e.strip()]
        if not elements:
            raise ValueError(f"Empty tier in ordering: '{notation}'")
        tiers.append(elements)

    if len(tiers) < 2:
        raise ValueError(f"Need at least 2 tiers, got {len(tiers)}: '{notation}'")

    return TierOrdering(tiers=tiers)


@dataclass
class PairResult:
    left: str
    right: str
    relation: str  # ">" or "~"
    left_value: float
    right_value: float
    satisfied: bool


@dataclass
class OrderingResult:
    """Result of evaluating a tier ordering against empirical data."""
    cross_tier_pairs: list[PairResult] = field(default_factory=list)
    within_tier_pairs: list[PairResult] = field(default_factory=list)
    unmatched_elements: list[str] = field(default_factory=list)

    @property
    def total_pairs(self) -> int:
        return len(self.cross_tier_pairs) + len(self.within_tier_pairs)

    @property
    def matched_pairs(self) -> int:
        return sum(1 for p in self.cross_tier_pairs if p.satisfied) + \
            sum(1 for p in self.within_tier_pairs if p.satisfied)

    @property
    def concordance(self) -> float:
        return self.matched_pairs / self.total_pairs if self.total_pairs > 0 else 0.0

    def node_mismatches(self) -> dict[str, dict[str, int]]:
        """Per-node mismatch frequency: which nodes cause violations."""
        stats: dict[str, dict[str, int]] = {}

        def _ensure(node):
            if node not in stats:
                stats[node] = {"left_violations": 0, "right_violations": 0, "appearances": 0}

        for p in self.cross_tier_pairs:
            _ensure(p.left)
            _ensure(p.right)
            stats[p.left]["appearances"] += 1
            stats[p.right]["appearances"] += 1
            if not p.satisfied:
                # Left was supposed to be larger but wasn't
                stats[p.left]["left_violations"] += 1
                stats[p.right]["right_violations"] += 1

        for p in self.within_tier_pairs:
            _ensure(p.left)
            _ensure(p.right)
            stats[p.left]["appearances"] += 1
            stats[p.right]["appearances"] += 1
            if not p.satisfied:
                # Both violated the ~ expectation
                stats[p.left]["left_violations"] += 1
                stats[p.right]["right_violations"] += 1

        # Only return nodes with violations
        return {k: v for k, v in stats.items()
                if v["left_violations"] > 0 or v["right_violations"] > 0}

    def to_dict(self) -> dict[str, Any]:
        return {
            "total_pairs": self.total_pairs,
            "cross_tier_pairs": len(self.cross_tier_pairs),
            "within_tier_pairs": len(self.within_tier_pairs),
            "matched_pairs": self.matched_pairs,
            "concordance": self.concordance,
            "unmatched_elements": self.unmatched_elements,
            "node_mismatches": self.node_mismatches(),
            "details": {
                "cross_tier": [
                    {"left": p.left, "right": p.right, "relation": p.relation,
                     "left_value": p.left_value, "right_value": p.right_value,
                     "satisfied": p.satisfied}
                    for p in self.cross_tier_pairs
                ],
                "within_tier": [
                    {"left": p.left, "right": p.right, "relation": p.relation,
                     "left_value": p.left_value, "right_value": p.right_value,
                     "satisfied": p.satisfied}
                    for p in self.within_tier_pairs
                ],
            },
        }


def evaluate_ordering(
        ordering: TierOrdering,
        effects: dict[str, float],
        equality_tolerance: float = 0.1,
) -> OrderingResult:
    """Evaluate a tier ordering against a map of element → empirical effect.

    Parameters
    ----------
    ordering
        Parsed tier notation.
    effects
        Map of element label (or threshold as string) to empirical effect value.
    equality_tolerance
        Relative tolerance for within-tier ~ checks.
        Two values are "approximately equal" if
        ``|a - b| / max(|a|, |b|) <= tolerance`` or both are near zero.
    """
    result = OrderingResult()

    def _lookup(elem: str) -> float | None:
        if elem in effects:
            return effects[elem]
        # Try as number (threshold_variants use numeric keys)
        try:
            num = float(elem)
            for k, v in effects.items():
                try:
                    if float(k) == num:
                        return v
                except (ValueError, TypeError):
                    continue
        except (ValueError, TypeError):
            pass
        # Try normalized label (nodePowerStatus_outage → nodePowerStatus=outage)
        norm = elem.replace("_", "=", 1) if "=" not in elem else elem
        for k, v in effects.items():
            if norm in k or elem in k:
                return v
        return None

    def _approx_equal(a: float, b: float) -> bool:
        denom = max(abs(a), abs(b))
        if denom < 1e-10:
            return True
        return abs(a - b) / denom <= equality_tolerance

    # Resolve all elements
    resolved: dict[str, float] = {}
    for tier in ordering.tiers:
        for elem in tier:
            val = _lookup(elem)
            if val is not None:
                resolved[elem] = val
            else:
                result.unmatched_elements.append(elem)

    # Cross-tier pairs: every element in tier i vs every element in tier j (i < j)
    for i, higher_tier in enumerate(ordering.tiers):
        for j in range(i + 1, len(ordering.tiers)):
            lower_tier = ordering.tiers[j]
            for h_elem in higher_tier:
                if h_elem not in resolved:
                    continue
                for l_elem in lower_tier:
                    if l_elem not in resolved:
                        continue
                    h_val = resolved[h_elem]
                    l_val = resolved[l_elem]
                    # Higher tier should have larger absolute effect
                    satisfied = abs(h_val) > abs(l_val)
                    result.cross_tier_pairs.append(PairResult(
                        left=h_elem, right=l_elem, relation=">",
                        left_value=h_val, right_value=l_val,
                        satisfied=satisfied,
                    ))

    # Within-tier pairs: all pairs within each tier should be ~ equal
    for tier in ordering.tiers:
        resolved_in_tier = [e for e in tier if e in resolved]
        for i, a in enumerate(resolved_in_tier):
            for b in resolved_in_tier[i + 1:]:
                a_val = resolved[a]
                b_val = resolved[b]
                satisfied = _approx_equal(a_val, b_val)
                result.within_tier_pairs.append(PairResult(
                    left=a, right=b, relation="~",
                    left_value=a_val, right_value=b_val,
                    satisfied=satisfied,
                ))

    return result
