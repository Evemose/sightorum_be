"""
Validation utilities for request validation.

Provides a structured validation framework with detailed error collection
for AI-to-AI communication.
"""

import re
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

from .exceptions import ValidationError


@dataclass
class ValidationResult:
    """Result of a validation operation."""

    is_valid: bool = True
    errors: dict[str, list[str]] = field(default_factory=dict)

    def add_error(self, field_name: str, message: str) -> None:
        self.is_valid = False
        if field_name not in self.errors:
            self.errors[field_name] = []
        self.errors[field_name].append(message)

    def merge(self, other: "ValidationResult") -> "ValidationResult":
        if not other.is_valid:
            self.is_valid = False
            for field_name, messages in other.errors.items():
                if field_name not in self.errors:
                    self.errors[field_name] = []
                self.errors[field_name].extend(messages)
        return self

    def raise_if_invalid(self) -> None:
        if not self.is_valid:
            raise ValidationError.multiple_errors(self.errors)

    def to_dict(self) -> dict[str, Any]:
        return {"is_valid": self.is_valid, "errors": self.errors}


class Validator:
    """Validator with fluent interface for building validation rules."""

    def __init__(self):
        self._result = ValidationResult()
        self._current_field: Optional[str] = None
        self._current_value: Any = None
        self._skip_remaining: bool = False

    def field(self, name: str, value: Any) -> "Validator":
        self._current_field = name
        self._current_value = value
        self._skip_remaining = False
        return self

    def required(self, message: str = "Field is required") -> "Validator":
        if self._skip_remaining:
            return self
        if self._current_value is None or (
                isinstance(self._current_value, str) and not self._current_value.strip()
        ):
            self._result.add_error(self._current_field, message)
            self._skip_remaining = True
        return self

    def not_empty(
            self, message: str = "Field must not be empty"
    ) -> "Validator":
        if self._skip_remaining:
            return self
        if isinstance(self._current_value, (list, dict, str)) and len(
                self._current_value
        ) == 0:
            self._result.add_error(self._current_field, message)
            self._skip_remaining = True
        return self

    def is_type(
            self, expected_type: type, type_name: Optional[str] = None
    ) -> "Validator":
        if self._skip_remaining:
            return self
        if self._current_value is not None and not isinstance(
                self._current_value, expected_type
        ):
            type_display = type_name or expected_type.__name__
            actual_type = type(self._current_value).__name__
            self._result.add_error(
                self._current_field,
                f"Expected type {type_display}, got {actual_type}",
            )
            self._skip_remaining = True
        return self

    def min_value(
            self, min_val: float, message: Optional[str] = None
    ) -> "Validator":
        if self._skip_remaining:
            return self
        if self._current_value is not None and self._current_value < min_val:
            msg = message or f"Value must be at least {min_val}"
            self._result.add_error(self._current_field, msg)
        return self

    def max_value(
            self, max_val: float, message: Optional[str] = None
    ) -> "Validator":
        if self._skip_remaining:
            return self
        if self._current_value is not None and self._current_value > max_val:
            msg = message or f"Value must be at most {max_val}"
            self._result.add_error(self._current_field, msg)
        return self

    def min_length(
            self, min_len: int, message: Optional[str] = None
    ) -> "Validator":
        if self._skip_remaining:
            return self
        if self._current_value is not None and len(self._current_value) < min_len:
            msg = message or f"Length must be at least {min_len}"
            self._result.add_error(self._current_field, msg)
        return self

    def max_length(
            self, max_len: int, message: Optional[str] = None
    ) -> "Validator":
        if self._skip_remaining:
            return self
        if self._current_value is not None and len(self._current_value) > max_len:
            msg = message or f"Length must be at most {max_len}"
            self._result.add_error(self._current_field, msg)
        return self

    def matches_pattern(
            self, pattern: str, message: Optional[str] = None
    ) -> "Validator":
        if self._skip_remaining:
            return self
        if self._current_value is not None and not re.match(
                pattern, str(self._current_value)
        ):
            msg = message or f"Value must match pattern: {pattern}"
            self._result.add_error(self._current_field, msg)
        return self

    def in_list(
            self, allowed_values: list[Any], message: Optional[str] = None
    ) -> "Validator":
        if self._skip_remaining:
            return self
        if self._current_value is not None and self._current_value not in allowed_values:
            msg = message or f"Value must be one of: {', '.join(str(v) for v in allowed_values)}"
            self._result.add_error(self._current_field, msg)
        return self

    def custom(
            self, predicate: Callable[[Any], bool], message: str
    ) -> "Validator":
        if self._skip_remaining:
            return self
        if self._current_value is not None and not predicate(self._current_value):
            self._result.add_error(self._current_field, message)
        return self

    def validate(self) -> ValidationResult:
        return self._result

    def raise_if_invalid(self) -> None:
        self._result.raise_if_invalid()


def validate_sql_query(sql: str) -> ValidationResult:
    """
    Validate SQL query for basic safety and correctness.

    Checks for:
    - Non-empty query
    - SELECT statement (only reads allowed)
    - No dangerous statements
    """
    result = ValidationResult()

    if not sql or not sql.strip():
        result.add_error("sql", "SQL query cannot be empty")
        return result

    normalized = sql.strip().upper()

    if not normalized.startswith("SELECT"):
        result.add_error(
            "sql",
            "Only SELECT statements are allowed. Query must start with SELECT.",
        )

    dangerous_patterns = [
        (r"\bDROP\b", "DROP statements are not allowed"),
        (r"\bDELETE\b", "DELETE statements are not allowed"),
        (r"\bTRUNCATE\b", "TRUNCATE statements are not allowed"),
        (r"\bINSERT\b", "INSERT statements are not allowed"),
        (r"\bUPDATE\b", "UPDATE statements are not allowed"),
        (r"\bALTER\b", "ALTER statements are not allowed"),
        (r"\bCREATE\b", "CREATE statements are not allowed"),
        (r"\bGRANT\b", "GRANT statements are not allowed"),
        (r"\bREVOKE\b", "REVOKE statements are not allowed"),
        (r";\s*\w", "Multiple statements are not allowed"),
    ]

    for pattern, message in dangerous_patterns:
        if re.search(pattern, normalized):
            result.add_error("sql", message)

    return result


def validate_model_name(name: str) -> ValidationResult:
    """
    Validate model name for storage compatibility.

    Requirements:
    - 1-128 characters
    - Alphanumeric, underscore, hyphen only
    - Must start with letter
    """
    result = ValidationResult()

    if not name:
        result.add_error("model_name", "Model name cannot be empty")
        return result

    if len(name) > 128:
        result.add_error(
            "model_name",
            f"Model name too long: {len(name)} characters. Maximum is 128.",
        )

    if not re.match(r"^[a-zA-Z][a-zA-Z0-9_-]*$", name):
        result.add_error(
            "model_name",
            "Model name must start with a letter and contain only "
            "alphanumeric characters, underscores, or hyphens",
        )

    return result
