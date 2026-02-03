"""
File-based model storage.

Provides persistence of trained models using joblib serialization.
"""

import joblib
import json
from core.exceptions import StorageError
from datetime import datetime
from models.base import TrainedModel
from pathlib import Path
from typing import Any, Optional


class FileModelStorage:
    """
    File-based storage for trained models.

    Stores models as joblib files with accompanying metadata JSON.
    """

    MODEL_EXTENSION = ".joblib"
    METADATA_EXTENSION = ".json"

    def __init__(
            self,
            base_directory: str = "./trained_models",
            create_if_missing: bool = True,
    ):
        """
        Initialize file storage.

        Args:
            base_directory: Directory for storing models
            create_if_missing: Create directory if it doesn't exist
        """
        self._base_dir = Path(base_directory)

        if create_if_missing:
            try:
                self._base_dir.mkdir(parents=True, exist_ok=True)
            except OSError as e:
                raise StorageError.directory_error(str(self._base_dir), str(e))
        elif not self._base_dir.exists():
            raise StorageError.directory_error(
                str(self._base_dir), "Directory does not exist"
            )

    def save(self, trained_model: TrainedModel) -> str:
        """
        Save a trained model to disk.

        Args:
            trained_model: The TrainedModel to save

        Returns:
            Path to the saved model file

        Raises:
            StorageError: If save fails
        """
        model_name = trained_model.model_name
        model_path = self._get_model_path(model_name)
        metadata_path = self._get_metadata_path(model_name)

        try:
            # Save model
            joblib.dump(trained_model.model, model_path)

            # Save metadata
            metadata = trained_model.get_metadata()
            metadata["storage_path"] = str(model_path)
            with open(metadata_path, "w") as f:
                json.dump(metadata, f, indent=2, default=str)

            return str(model_path)

        except Exception as e:
            # Clean up partial saves
            if model_path.exists():
                model_path.unlink()
            if metadata_path.exists():
                metadata_path.unlink()
            raise StorageError.save_failed(model_name, str(e))

    def load(self, model_name: str) -> tuple[Any, dict[str, Any]]:
        """
        Load a trained model from disk.

        Args:
            model_name: Name of the model to load

        Returns:
            Tuple of (model, metadata dict)

        Raises:
            StorageError: If load fails
        """
        model_path = self._get_model_path(model_name)
        metadata_path = self._get_metadata_path(model_name)

        if not model_path.exists():
            raise StorageError.load_failed(
                model_name, f"Model file not found at {model_path}"
            )

        try:
            model = joblib.load(model_path)

            metadata = {}
            if metadata_path.exists():
                with open(metadata_path, "r") as f:
                    metadata = json.load(f)

            return model, metadata

        except Exception as e:
            raise StorageError.load_failed(model_name, str(e))

    def exists(self, model_name: str) -> bool:
        """Check if a model exists in storage."""
        return self._get_model_path(model_name).exists()

    def delete(self, model_name: str) -> bool:
        """
        Delete a model from storage.

        Args:
            model_name: Name of the model to delete

        Returns:
            True if deleted, False if not found
        """
        model_path = self._get_model_path(model_name)
        metadata_path = self._get_metadata_path(model_name)

        deleted = False
        if model_path.exists():
            model_path.unlink()
            deleted = True
        if metadata_path.exists():
            metadata_path.unlink()
            deleted = True

        return deleted

    def list_models(self) -> list[dict[str, Any]]:
        """
        List all stored models with their metadata.

        Returns:
            List of model metadata dictionaries
        """
        models = []
        for model_file in self._base_dir.glob(f"*{self.MODEL_EXTENSION}"):
            model_name = model_file.stem
            metadata_path = self._get_metadata_path(model_name)

            model_info = {
                "model_name": model_name,
                "model_path": str(model_file),
                "size_bytes": model_file.stat().st_size,
                "modified_at": datetime.fromtimestamp(
                    model_file.stat().st_mtime
                ).isoformat(),
            }

            if metadata_path.exists():
                try:
                    with open(metadata_path, "r") as f:
                        metadata = json.load(f)
                    model_info.update(metadata)
                except json.JSONDecodeError:
                    pass

            models.append(model_info)

        return sorted(models, key=lambda x: x.get("created_at", ""), reverse=True)

    def get_metadata(self, model_name: str) -> Optional[dict[str, Any]]:
        """
        Get metadata for a model without loading the model itself.

        Args:
            model_name: Name of the model

        Returns:
            Metadata dict or None if not found
        """
        metadata_path = self._get_metadata_path(model_name)
        if not metadata_path.exists():
            return None

        try:
            with open(metadata_path, "r") as f:
                return json.load(f)
        except json.JSONDecodeError:
            return None

    def _get_model_path(self, model_name: str) -> Path:
        """Get the full path for a model file."""
        return self._base_dir / f"{model_name}{self.MODEL_EXTENSION}"

    def _get_metadata_path(self, model_name: str) -> Path:
        """Get the full path for a metadata file."""
        return self._base_dir / f"{model_name}{self.METADATA_EXTENSION}"

    def get_storage_info(self) -> dict[str, Any]:
        """Get information about the storage."""
        models = self.list_models()
        total_size = sum(m.get("size_bytes", 0) for m in models)

        return {
            "base_directory": str(self._base_dir),
            "model_count": len(models),
            "total_size_bytes": total_size,
            "total_size_mb": round(total_size / (1024 * 1024), 2),
        }
