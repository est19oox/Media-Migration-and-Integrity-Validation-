#!/usr/bin/env python3
"""Render non-secret ECS Terraform inputs from managed-data state and CI variables."""

import json
import os
import re
import sys
from pathlib import Path


def required(name):
    value = os.environ.get(name, "").strip()
    if not value:
        raise ValueError(f"Missing required repository variable: {name}")
    return value


def output(outputs, name):
    try:
        return outputs[name]["value"]
    except (KeyError, TypeError) as exc:
        raise ValueError(f"Managed-data Terraform state has no output {name}; initialize it first") from exc


def render(outputs, env):
    revision = env["GITHUB_SHA"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("GITHUB_SHA must be a full Git commit SHA")
    account = env["AWS_ACCOUNT_ID"]
    region = env["AWS_REGION"]
    repository = env.get("ECR_REPOSITORY", "mmiv")
    if not re.fullmatch(r"[0-9]{12}", account):
        raise ValueError("AWS_ACCOUNT_ID must be a 12-digit AWS account ID")
    if not re.fullmatch(r"[a-z0-9-]+", region) or not re.fullmatch(r"[a-z0-9-_]+", repository):
        raise ValueError("Invalid AWS region or ECR repository name")
    bucket = env["MMIV_BUCKET_NAME"]
    demo_single_az = env.get("MMIV_DEMO_SINGLE_AZ", "true").lower()
    start_services = env.get("MMIV_START_SERVICES", "true").lower()
    if demo_single_az not in ("true", "false"):
        raise ValueError("MMIV_DEMO_SINGLE_AZ must be true or false")
    if start_services not in ("true", "false"):
        raise ValueError("MMIV_START_SERVICES must be true or false")
    return {
        "region": region,
        "vpc_id": env["MMIV_VPC_ID"],
        "primary_public_subnet_id": env["MMIV_PRIMARY_PUBLIC_SUBNET_ID"],
        "secondary_public_subnet_id": env["MMIV_SECONDARY_PUBLIC_SUBNET_ID"],
        "task_security_group_id": output(outputs, "task_security_group_id"),
        "image_repository_url": f"{account}.dkr.ecr.{region}.amazonaws.com/{repository}",
        "image_revision": revision,
        "start_services": start_services == "true",
        "demo_single_az": demo_single_az == "true",
        "bucket_name": bucket,
        "bucket_arn": f"arn:aws:s3:::{bucket}",
        "oracle_jdbc_url": output(outputs, "oracle_jdbc_url"),
        "oracle_master_secret_arn": output(outputs, "oracle_master_secret_arn"),
        "rabbit_host": output(outputs, "rabbit_host"),
        "redis_host": output(outputs, "redis_host"),
        "rabbit_port": output(outputs, "rabbit_port"),
        "redis_port": output(outputs, "redis_port"),
        "rabbit_tls": True,
        "redis_tls": True,
        "gateway_cookie_secure": True,
        "jwt_issuer": env["MMIV_JWT_ISSUER"],
        "jwt_key_id": env["MMIV_JWT_KEY_ID"],
        "data_kms_key_arn": output(outputs, "kms_key_arn"),
        "secret_arns": {
            "rabbit_password": output(outputs, "rabbit_password_arn"),
            "user_access_db_password": output(outputs, "user_access_db_password_arn"),
            "discovery_db_password": output(outputs, "discovery_db_password_arn"),
            "integrity_db_password": output(outputs, "integrity_db_password_arn"),
            "mongo_uri": output(outputs, "mongo_uri_arn"),
            "redis_password": output(outputs, "redis_password_arn"),
            "jwt_private_key_pem": output(outputs, "jwt_private_key_secret_arn"),
            "jwt_public_key_pem": output(outputs, "jwt_public_key_secret_arn"),
        },
    }


def main():
    source = Path(sys.argv[1])
    target = Path(sys.argv[2])
    outputs = json.loads(source.read_text())
    names = (
        "GITHUB_SHA", "AWS_ACCOUNT_ID", "AWS_REGION", "MMIV_VPC_ID",
        "MMIV_PRIMARY_PUBLIC_SUBNET_ID", "MMIV_SECONDARY_PUBLIC_SUBNET_ID",
        "MMIV_BUCKET_NAME", "MMIV_JWT_ISSUER", "MMIV_JWT_KEY_ID",
    )
    env = {name: required(name) for name in names}
    env["ECR_REPOSITORY"] = os.environ.get("ECR_REPOSITORY", "mmiv")
    env["MMIV_DEMO_SINGLE_AZ"] = os.environ.get("MMIV_DEMO_SINGLE_AZ", "true")
    env["MMIV_START_SERVICES"] = os.environ.get("MMIV_START_SERVICES", "true")
    if target.exists():
        raise ValueError(f"Refusing to overwrite existing variable file: {target}")
    target.touch(mode=0o600)
    target.write_text(json.dumps(render(outputs, env), indent=2) + "\n")
    print(f"Rendered ECS inputs for commit {env['GITHUB_SHA']}")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError) as exc:
        raise SystemExit(str(exc)) from exc
