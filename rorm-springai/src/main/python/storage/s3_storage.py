import boto3
import io
import joblib
import json
from botocore.exceptions import ClientError
from core.exceptions import StorageError
from datetime import datetime
from models.base import TrainedModel
from typing import Any, Optional


class S3ModelStorage:
    MODEL_EXTENSION = ".joblib"
    METADATA_EXTENSION = ".json"

    def __init__(
            self,
            bucket_name: str,
            region_name: Optional[str] = None,
            prefix: str = "models/",
    ):
        self._bucket = bucket_name
        self._prefix = prefix if prefix.endswith("/") else f"{prefix}/"
        self._client = boto3.client("s3", region_name=region_name)

    def save(self, trained_model: TrainedModel) -> str:
        model_name = trained_model.model_name
        model_key = self._model_key(model_name)
        metadata_key = self._metadata_key(model_name)
        model_uri = self._uri(model_key)

        try:
            buffer = io.BytesIO()
            joblib.dump(trained_model.model, buffer)
            buffer.seek(0)
            self._client.put_object(Bucket=self._bucket, Key=model_key, Body=buffer.getvalue())

            metadata = trained_model.get_metadata()
            metadata["storage_path"] = model_uri
            self._client.put_object(
                Bucket=self._bucket,
                Key=metadata_key,
                Body=json.dumps(metadata, indent=2, default=str).encode("utf-8"),
                ContentType="application/json",
            )

            return model_uri

        except Exception as e:
            for key in (model_key, metadata_key):
                try:
                    self._client.delete_object(Bucket=self._bucket, Key=key)
                except Exception:
                    pass
            raise StorageError.save_failed(model_name, str(e))

    def load(self, model_name: str) -> tuple[Any, dict[str, Any]]:
        model_key = self._model_key(model_name)
        metadata_key = self._metadata_key(model_name)

        try:
            response = self._client.get_object(Bucket=self._bucket, Key=model_key)
            model = joblib.load(io.BytesIO(response["Body"].read()))
        except ClientError as e:
            if _is_not_found(e):
                raise StorageError.load_failed(
                    model_name, f"Model object not found at s3://{self._bucket}/{model_key}"
                )
            raise StorageError.load_failed(model_name, str(e))
        except Exception as e:
            raise StorageError.load_failed(model_name, str(e))

        metadata: dict[str, Any] = {}
        try:
            response = self._client.get_object(Bucket=self._bucket, Key=metadata_key)
            metadata = json.loads(response["Body"].read().decode("utf-8"))
        except ClientError as e:
            if not _is_not_found(e):
                raise StorageError.load_failed(model_name, str(e))

        return model, metadata

    def exists(self, model_name: str) -> bool:
        try:
            self._client.head_object(Bucket=self._bucket, Key=self._model_key(model_name))
            return True
        except ClientError as e:
            if _is_not_found(e):
                return False
            raise

    def delete(self, model_name: str) -> bool:
        deleted = False
        for key in (self._model_key(model_name), self._metadata_key(model_name)):
            try:
                self._client.head_object(Bucket=self._bucket, Key=key)
            except ClientError as e:
                if _is_not_found(e):
                    continue
                raise
            self._client.delete_object(Bucket=self._bucket, Key=key)
            deleted = True
        return deleted

    def list_models(self) -> list[dict[str, Any]]:
        models: list[dict[str, Any]] = []
        paginator = self._client.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=self._bucket, Prefix=self._prefix):
            for obj in page.get("Contents", []) or []:
                key = obj["Key"]
                if not key.endswith(self.MODEL_EXTENSION):
                    continue
                model_name = key[len(self._prefix):-len(self.MODEL_EXTENSION)]
                model_info: dict[str, Any] = {
                    "model_name": model_name,
                    "model_path": self._uri(key),
                    "size_bytes": obj["Size"],
                    "modified_at": obj["LastModified"].isoformat()
                    if isinstance(obj["LastModified"], datetime)
                    else str(obj["LastModified"]),
                }
                metadata = self.get_metadata(model_name)
                if metadata:
                    model_info.update(metadata)
                models.append(model_info)

        return sorted(models, key=lambda x: x.get("created_at", ""), reverse=True)

    def get_metadata(self, model_name: str) -> Optional[dict[str, Any]]:
        try:
            response = self._client.get_object(
                Bucket=self._bucket, Key=self._metadata_key(model_name)
            )
            return json.loads(response["Body"].read().decode("utf-8"))
        except ClientError as e:
            if _is_not_found(e):
                return None
            raise
        except json.JSONDecodeError:
            return None

    def get_storage_info(self) -> dict[str, Any]:
        models = self.list_models()
        total_size = sum(m.get("size_bytes", 0) for m in models)
        return {
            "bucket": self._bucket,
            "prefix": self._prefix,
            "model_count": len(models),
            "total_size_bytes": total_size,
            "total_size_mb": round(total_size / (1024 * 1024), 2),
        }

    def _model_key(self, model_name: str) -> str:
        return f"{self._prefix}{model_name}{self.MODEL_EXTENSION}"

    def _metadata_key(self, model_name: str) -> str:
        return f"{self._prefix}{model_name}{self.METADATA_EXTENSION}"

    def _uri(self, key: str) -> str:
        return f"s3://{self._bucket}/{key}"


def _is_not_found(error: ClientError) -> bool:
    code = error.response.get("Error", {}).get("Code", "")
    status = error.response.get("ResponseMetadata", {}).get("HTTPStatusCode")
    return code in ("404", "NoSuchKey", "NotFound") or status == 404
