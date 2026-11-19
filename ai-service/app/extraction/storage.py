"""Reading documents from S3 by key (ADR 0003: by key, never by pre-signed URL)."""

import boto3
from botocore.exceptions import ClientError

from app.config import Settings
from app.extraction.pipeline import DocumentMissing


def s3_client(settings: Settings):
    return boto3.client("s3", region_name=settings.aws_region, endpoint_url=settings.s3_endpoint or None)


def sqs_client(settings: Settings):
    return boto3.client("sqs", region_name=settings.aws_region, endpoint_url=settings.sqs_endpoint or None)


def s3_fetcher(client):
    def fetch(bucket: str, key: str) -> bytes:
        try:
            return client.get_object(Bucket=bucket, Key=key)["Body"].read()
        except ClientError as e:
            if e.response.get("Error", {}).get("Code") in {"NoSuchKey", "404", "NoSuchBucket"}:
                raise DocumentMissing(key) from e
            raise

    return fetch
