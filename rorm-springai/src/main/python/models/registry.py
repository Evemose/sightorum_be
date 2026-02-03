"""
Model Trainer Registry for plugin-style model discovery.

Provides a centralized registry for model trainers with
dependency injection support for loose coupling.
"""

from core.exceptions import ModelNotFoundError
from typing import Callable, Type

from .base import ModelCategory, ModelTrainer


class ModelTrainerRegistry:
    """
    Registry for model trainers.

    Supports:
    - Registration of trainer classes by name
    - Factory functions for lazy instantiation
    - Discovery by name or category
    - Plugin-style extensibility
    """

    def __init__(self):
        self._trainers: dict[str, Type[ModelTrainer]] = {}
        self._factories: dict[str, Callable[[], ModelTrainer]] = {}

    def register(
            self,
            name: str,
            trainer_class: Type[ModelTrainer],
    ) -> None:
        """
        Register a trainer class.

        Args:
            name: Unique identifier for the trainer
            trainer_class: The ModelTrainer subclass to register
        """
        self._trainers[name] = trainer_class

    def register_factory(
            self,
            name: str,
            factory: Callable[[], ModelTrainer],
    ) -> None:
        """
        Register a factory function for lazy instantiation.

        Args:
            name: Unique identifier for the trainer
            factory: Callable that returns a ModelTrainer instance
        """
        self._factories[name] = factory

    def get(self, name: str) -> ModelTrainer:
        """
        Get a trainer instance by name.

        Args:
            name: The trainer identifier

        Returns:
            ModelTrainer instance

        Raises:
            ModelNotFoundError: If trainer not registered
        """
        # Check factories first
        if name in self._factories:
            return self._factories[name]()

        # Check classes
        if name in self._trainers:
            return self._trainers[name]()

        raise ModelNotFoundError.not_registered(name, self.list_models())

    def has(self, name: str) -> bool:
        """Check if a trainer is registered."""
        return name in self._trainers or name in self._factories

    def list_models(self) -> list[str]:
        """List all registered model names."""
        return sorted(set(self._trainers.keys()) | set(self._factories.keys()))

    def list_by_category(self, category: ModelCategory) -> list[str]:
        """
        List models by category.

        Args:
            category: The category to filter by

        Returns:
            List of model names in the category
        """
        result = []
        for name in self.list_models():
            trainer = self.get(name)
            if trainer.category == category:
                result.append(name)
        return result

    def get_model_info(self, name: str) -> dict:
        """
        Get detailed information about a model.

        Args:
            name: The trainer identifier

        Returns:
            Dictionary with model information
        """
        trainer = self.get(name)
        return {
            "name": name,
            "model_type": trainer.model_type,
            "category": trainer.category.value,
            "requires_target": trainer.requires_target,
            "minimum_rows": trainer.minimum_rows,
            "default_params": trainer.default_params,
            "param_schema": trainer.param_schema,
        }

    def get_all_models_info(self) -> dict[str, dict]:
        """Get information about all registered models."""
        return {name: self.get_model_info(name) for name in self.list_models()}

    def unregister(self, name: str) -> bool:
        """
        Unregister a trainer.

        Args:
            name: The trainer identifier

        Returns:
            True if unregistered, False if not found
        """
        removed = False
        if name in self._trainers:
            del self._trainers[name]
            removed = True
        if name in self._factories:
            del self._factories[name]
            removed = True
        return removed

    def clear(self) -> None:
        """Clear all registrations."""
        self._trainers.clear()
        self._factories.clear()


def model_trainer(name: str):
    """
    Decorator for registering model trainers.

    Usage:
        @model_trainer("my_model")
        class MyModelTrainer(ModelTrainer):
            ...
    """

    def decorator(cls: Type[ModelTrainer]) -> Type[ModelTrainer]:
        # This would be used with a global registry
        # For now, just mark the class
        cls._registered_name = name
        return cls

    return decorator
