"""Apriori association rule mining model trainer."""

import polars as pl
from core.exceptions import ModelTrainingError
from mlxtend.frequent_patterns import apriori, association_rules
from mlxtend.preprocessing import TransactionEncoder
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class AprioriTrainer(ModelTrainer):
    """
    Apriori Association Rule Mining trainer.

    Discovers frequent itemsets and generates association rules
    from transactional data.

    Input data format options:
    1. Binary-encoded: Each column is an item, values are 0/1
    2. Transaction format: Single column with comma-separated items

    Output includes:
    - Frequent itemsets with support values
    - Association rules with confidence, lift, leverage
    """

    @property
    def model_type(self) -> str:
        return "apriori"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.ASSOCIATION

    @property
    def requires_target(self) -> bool:
        return False

    @property
    def minimum_rows(self) -> int:
        return 10  # Need enough transactions

    @property
    def default_params(self) -> dict[str, Any]:
        return {
            "min_support": 0.1,
            "min_confidence": 0.5,
            "min_lift": 1.0,
            "max_itemset_length": None,
            "metric": "confidence",
            "input_format": "binary",  # "binary" or "transactions"
            "transaction_column": None,  # Column name if format is "transactions"
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
                "default": None,
                "min": 1,
                "max": 20,
                "description": "Maximum length of itemsets to generate (None = unlimited)",
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
        """
        Prepare data for Apriori - override to handle transaction format.
        """
        # For Apriori, we just return the DataFrame as-is
        # The actual transformation happens in train()
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
        """Train Apriori model to find frequent itemsets and rules."""
        import pandas as pd

        input_format = params["input_format"]

        # Convert to binary-encoded pandas DataFrame
        if input_format == "transactions":
            df_binary = self._convert_transactions_to_binary(X, params)
        else:
            # Already binary encoded
            df_binary = X.to_pandas()
            # Ensure boolean type
            df_binary = df_binary.astype(bool)

        # Validate data
        if df_binary.empty or df_binary.shape[1] == 0:
            raise ModelTrainingError(
                model_type=self.model_type,
                message="No valid items found in dataset after preprocessing",
            )

        # Run Apriori algorithm
        max_len = params.get("max_itemset_length")
        frequent_itemsets = apriori(
            df_binary,
            min_support=params["min_support"],
            use_colnames=True,
            max_len=max_len,
        )

        if frequent_itemsets.empty:
            raise ModelTrainingError(
                model_type=self.model_type,
                message=f"No frequent itemsets found with min_support={params['min_support']}. "
                        f"Try lowering the min_support threshold.",
                details={"min_support": params["min_support"], "n_items": df_binary.shape[1]},
            )

        # Generate association rules
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

        # Build result object
        result = AprioriResult(
            frequent_itemsets=frequent_itemsets,
            rules=rules,
            item_names=list(df_binary.columns),
        )

        # Compute metrics
        metrics = self._compute_metrics(df_binary, frequent_itemsets, rules, params)

        return result, metrics

    def _convert_transactions_to_binary(
            self, df: pl.DataFrame, params: dict[str, Any]
    ) -> "pd.DataFrame":
        """Convert transaction format to binary-encoded format."""
        import pandas as pd

        tx_col = params.get("transaction_column")
        separator = params["transaction_separator"]

        if tx_col is None:
            # Use first column if not specified
            tx_col = df.columns[0]

        if tx_col not in df.columns:
            raise ModelTrainingError(
                model_type=self.model_type,
                message=f"Transaction column '{tx_col}' not found. Available: {df.columns}",
            )

        # Extract transactions as list of lists
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

        # Use TransactionEncoder to convert to binary format
        te = TransactionEncoder()
        te_array = te.fit_transform(transactions)
        df_binary = pd.DataFrame(te_array, columns=te.columns_)

        return df_binary

    def _compute_metrics(
            self,
            df_binary: "pd.DataFrame",
            frequent_itemsets: "pd.DataFrame",
            rules: "pd.DataFrame",
            params: dict[str, Any],
    ) -> dict[str, Any]:
        """Compute training metrics."""
        metrics = {
            "n_transactions": int(df_binary.shape[0]),
            "n_items": int(df_binary.shape[1]),
            "n_frequent_itemsets": int(len(frequent_itemsets)),
            "n_rules": int(len(rules)),
            "min_support": params["min_support"],
            "min_confidence": params["min_confidence"],
            "min_lift": params["min_lift"],
        }

        # Itemset statistics
        if not frequent_itemsets.empty:
            itemset_lengths = frequent_itemsets["itemsets"].apply(len)
            metrics["itemset_stats"] = {
                "min_length": int(itemset_lengths.min()),
                "max_length": int(itemset_lengths.max()),
                "avg_length": float(itemset_lengths.mean()),
            }

            # Top itemsets by support
            top_itemsets = frequent_itemsets.nlargest(10, "support")
            metrics["top_itemsets"] = [
                {
                    "items": list(row["itemsets"]),
                    "support": float(row["support"]),
                }
                for _, row in top_itemsets.iterrows()
            ]

        # Rule statistics
        if not rules.empty:
            metrics["rule_stats"] = {
                "avg_confidence": float(rules["confidence"].mean()),
                "avg_lift": float(rules["lift"].mean()),
                "max_confidence": float(rules["confidence"].max()),
                "max_lift": float(rules["lift"].max()),
            }

            # Top rules by lift
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


class AprioriResult:
    """
    Container for Apriori results.

    Stores frequent itemsets and association rules for later use.
    """

    def __init__(
            self,
            frequent_itemsets: "pd.DataFrame",
            rules: "pd.DataFrame",
            item_names: list[str],
    ):
        self.frequent_itemsets = frequent_itemsets
        self.rules = rules
        self.item_names = item_names

    def get_itemsets(self, min_support: float = 0.0) -> list[dict]:
        """Get frequent itemsets above minimum support."""
        filtered = self.frequent_itemsets[
            self.frequent_itemsets["support"] >= min_support
            ]
        return [
            {"items": list(row["itemsets"]), "support": float(row["support"])}
            for _, row in filtered.iterrows()
        ]

    def get_rules(
            self,
            min_confidence: float = 0.0,
            min_lift: float = 0.0,
    ) -> list[dict]:
        """Get association rules above minimum thresholds."""
        filtered = self.rules[
            (self.rules["confidence"] >= min_confidence)
            & (self.rules["lift"] >= min_lift)
            ]
        return [
            {
                "antecedents": list(row["antecedents"]),
                "consequents": list(row["consequents"]),
                "support": float(row["support"]),
                "confidence": float(row["confidence"]),
                "lift": float(row["lift"]),
            }
            for _, row in filtered.iterrows()
        ]

    def predict_associations(self, items: list[str]) -> list[dict]:
        """
        Find items likely to be associated with given items.

        Args:
            items: List of items in the current basket

        Returns:
            List of recommended items with confidence scores
        """
        items_set = frozenset(items)
        recommendations = []

        for _, rule in self.rules.iterrows():
            antecedents = rule["antecedents"]
            if antecedents.issubset(items_set):
                for item in rule["consequents"]:
                    if item not in items_set:
                        recommendations.append(
                            {
                                "item": item,
                                "confidence": float(rule["confidence"]),
                                "lift": float(rule["lift"]),
                                "based_on": list(antecedents),
                            }
                        )

        # Sort by confidence and deduplicate
        seen = set()
        unique_recommendations = []
        for rec in sorted(recommendations, key=lambda x: -x["confidence"]):
            if rec["item"] not in seen:
                seen.add(rec["item"])
                unique_recommendations.append(rec)

        return unique_recommendations
