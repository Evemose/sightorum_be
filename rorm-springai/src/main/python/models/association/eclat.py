"""Eclat association rule mining model trainer."""

import pandas as pd
import polars as pl
from core.exceptions import ModelTrainingError
from mlxtend.frequent_patterns import association_rules
from mlxtend.preprocessing import TransactionEncoder
from typing import Any, Optional

from .apriori import AprioriResult
from ..base import ModelTrainer, ModelCategory


class EclatTrainer(ModelTrainer):
    """
    Eclat (Equivalence Class Transformation) Association Rule Mining trainer.

    Uses vertical data representation (tidsets) for efficient mining:
    - Stores transaction IDs for each item
    - Uses set intersection for support counting
    - Depth-first search for itemset generation

    Advantages over Apriori:
    - No candidate generation
    - Faster for dense datasets
    - Memory efficient for sparse datasets

    Best for: Medium-sized datasets with moderate item counts.
    """

    @property
    def model_type(self) -> str:
        return "eclat"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.ASSOCIATION

    @property
    def requires_target(self) -> bool:
        return False

    @property
    def minimum_rows(self) -> int:
        return 10

    @property
    def default_params(self) -> dict[str, Any]:
        return {
            "min_support": 0.1,
            "min_confidence": 0.5,
            "min_lift": 1.0,
            "max_itemset_length": 5,
            "metric": "confidence",
            "input_format": "binary",
            "transaction_column": None,
            "transaction_separator": ",",
        }

    @property
    def param_schema(self) -> dict[str, dict[str, Any]]:
        return {
            "min_support": {
                "type": "float",
                "required": False,
                "default": 0.1,
                "min": 0.001,
                "max": 1.0,
                "description": "Minimum support threshold for frequent itemsets (0-1)",
            },
            "min_confidence": {
                "type": "float",
                "required": False,
                "default": 0.5,
                "min": 0.0,
                "max": 1.0,
                "description": "Minimum confidence threshold for rules (0-1)",
            },
            "min_lift": {
                "type": "float",
                "required": False,
                "default": 1.0,
                "min": 0.0,
                "description": "Minimum lift threshold for rules (>1 indicates positive association)",
            },
            "max_itemset_length": {
                "type": "int",
                "required": False,
                "default": 5,
                "min": 1,
                "max": 10,
                "description": "Maximum length of itemsets to generate",
            },
            "metric": {
                "type": "str",
                "required": False,
                "default": "confidence",
                "choices": ["support", "confidence", "lift", "leverage", "conviction"],
                "description": "Metric for rule filtering",
            },
            "input_format": {
                "type": "str",
                "required": False,
                "default": "binary",
                "choices": ["binary", "transactions"],
                "description": "Input format: binary-encoded columns or transaction strings",
            },
            "transaction_column": {
                "type": "str",
                "required": False,
                "default": None,
                "description": "Column containing transaction items (if format=transactions)",
            },
            "transaction_separator": {
                "type": "str",
                "required": False,
                "default": ",",
                "description": "Separator for items in transaction strings",
            },
        }

    def prepare_data(
            self,
            df: pl.DataFrame,
            feature_columns: Optional[list[str]],
            target_column: Optional[str],
    ) -> tuple[pl.DataFrame, Optional[pl.Series]]:
        """Prepare data for Eclat."""
        if feature_columns:
            df = df.select(feature_columns)

        if df.height < self.minimum_rows:
            raise ModelTrainingError.insufficient_data(
                self.model_type, df.height, self.minimum_rows
            )

        return df, None

    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Train Eclat model to find frequent itemsets and rules."""
        input_format = params["input_format"]

        # Convert to binary-encoded pandas DataFrame
        if input_format == "transactions":
            df_binary = self._convert_transactions_to_binary(X, params)
        else:
            df_binary = X.to_pandas()
            df_binary = df_binary.astype(bool)

        if df_binary.empty or df_binary.shape[1] == 0:
            raise ModelTrainingError(
                model_type=self.model_type,
                message="No valid items found in dataset after preprocessing",
            )

        # Run Eclat algorithm
        n_transactions = len(df_binary)
        min_support_count = int(params["min_support"] * n_transactions)
        max_len = params.get("max_itemset_length", 5)

        frequent_itemsets = self._eclat(
            df_binary,
            min_support_count=min_support_count,
            max_length=max_len,
        )

        if frequent_itemsets.empty:
            raise ModelTrainingError(
                model_type=self.model_type,
                message=f"No frequent itemsets found with min_support={params['min_support']}. "
                        f"Try lowering the min_support threshold.",
                details={"min_support": params["min_support"], "n_items": df_binary.shape[1]},
            )

        # Generate association rules using mlxtend
        rules = association_rules(
            frequent_itemsets,
            metric=params["metric"],
            min_threshold=params["min_confidence"]
            if params["metric"] == "confidence"
            else params["min_lift"],
        )

        # Filter by additional thresholds
        if not rules.empty:
            rules = rules[rules["confidence"] >= params["min_confidence"]]
            rules = rules[rules["lift"] >= params["min_lift"]]

        result = AprioriResult(
            frequent_itemsets=frequent_itemsets,
            rules=rules,
            item_names=list(df_binary.columns),
        )

        metrics = self._compute_metrics(df_binary, frequent_itemsets, rules, params)

        return result, metrics

    def _eclat(
            self,
            df: pd.DataFrame,
            min_support_count: int,
            max_length: int,
    ) -> pd.DataFrame:
        """
        Eclat algorithm implementation using vertical data format.

        Uses tidsets (transaction ID sets) for efficient support counting.
        """
        n_transactions = len(df)
        items = df.columns.tolist()

        # Build vertical representation: item -> set of transaction IDs
        tidsets: dict[str, set[int]] = {}
        for item in items:
            tidsets[item] = set(df.index[df[item]].tolist())

        # Filter items by minimum support
        frequent_1_itemsets = {
            frozenset([item]): tids
            for item, tids in tidsets.items()
            if len(tids) >= min_support_count
        }

        if not frequent_1_itemsets:
            return pd.DataFrame(columns=["support", "itemsets"])

        all_frequent = dict(frequent_1_itemsets)

        # Generate larger itemsets using depth-first search
        current_itemsets = frequent_1_itemsets

        for k in range(2, max_length + 1):
            next_itemsets = {}
            itemset_list = list(current_itemsets.keys())

            for i, itemset1 in enumerate(itemset_list):
                for itemset2 in itemset_list[i + 1:]:
                    # Combine itemsets
                    new_itemset = itemset1 | itemset2
                    if len(new_itemset) != k:
                        continue

                    # Skip if already processed
                    if new_itemset in next_itemsets or new_itemset in all_frequent:
                        continue

                    # Calculate support via tidset intersection
                    new_tidset = current_itemsets[itemset1] & current_itemsets[itemset2]

                    if len(new_tidset) >= min_support_count:
                        next_itemsets[new_itemset] = new_tidset

            if not next_itemsets:
                break

            all_frequent.update(next_itemsets)
            current_itemsets = next_itemsets

        # Convert to DataFrame format compatible with mlxtend
        results = []
        for itemset, tidset in all_frequent.items():
            support = len(tidset) / n_transactions
            results.append({"support": support, "itemsets": itemset})

        return pd.DataFrame(results)

    def _convert_transactions_to_binary(
            self, df: pl.DataFrame, params: dict[str, Any]
    ) -> pd.DataFrame:
        """Convert transaction format to binary-encoded format."""
        tx_col = params.get("transaction_column")
        separator = params["transaction_separator"]

        if tx_col is None:
            tx_col = df.columns[0]

        if tx_col not in df.columns:
            raise ModelTrainingError(
                model_type=self.model_type,
                message=f"Transaction column '{tx_col}' not found. Available: {df.columns}",
            )

        transactions = []
        for row in df[tx_col].to_list():
            if row is not None:
                items = [item.strip() for item in str(row).split(separator) if item.strip()]
                transactions.append(items)

        if not transactions:
            raise ModelTrainingError(
                model_type=self.model_type,
                message="No valid transactions found in the data",
            )

        te = TransactionEncoder()
        te_array = te.fit_transform(transactions)
        df_binary = pd.DataFrame(te_array, columns=te.columns_)

        return df_binary

    def _compute_metrics(
            self,
            df_binary: pd.DataFrame,
            frequent_itemsets: pd.DataFrame,
            rules: pd.DataFrame,
            params: dict[str, Any],
    ) -> dict[str, Any]:
        """Compute training metrics."""
        metrics = {
            "algorithm": "eclat",
            "n_transactions": int(df_binary.shape[0]),
            "n_items": int(df_binary.shape[1]),
            "n_frequent_itemsets": int(len(frequent_itemsets)),
            "n_rules": int(len(rules)),
            "min_support": params["min_support"],
            "min_confidence": params["min_confidence"],
            "min_lift": params["min_lift"],
        }

        if not frequent_itemsets.empty:
            itemset_lengths = frequent_itemsets["itemsets"].apply(len)
            metrics["itemset_stats"] = {
                "min_length": int(itemset_lengths.min()),
                "max_length": int(itemset_lengths.max()),
                "avg_length": float(itemset_lengths.mean()),
            }

            top_itemsets = frequent_itemsets.nlargest(10, "support")
            metrics["top_itemsets"] = [
                {"items": list(row["itemsets"]), "support": float(row["support"])}
                for _, row in top_itemsets.iterrows()
            ]

        if not rules.empty:
            metrics["rule_stats"] = {
                "avg_confidence": float(rules["confidence"].mean()),
                "avg_lift": float(rules["lift"].mean()),
                "max_confidence": float(rules["confidence"].max()),
                "max_lift": float(rules["lift"].max()),
            }

            top_rules = rules.nlargest(10, "lift")
            metrics["top_rules"] = [
                {
                    "antecedents": list(row["antecedents"]),
                    "consequents": list(row["consequents"]),
                    "support": float(row["support"]),
                    "confidence": float(row["confidence"]),
                    "lift": float(row["lift"]),
                }
                for _, row in top_rules.iterrows()
            ]

        return metrics
