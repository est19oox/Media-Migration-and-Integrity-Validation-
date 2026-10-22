#!/usr/bin/env python3
"""Initialize a new RDS Oracle database from a VPC-connected runner.

Requires boto3 and python-oracledb. Secrets never appear in shell arguments.
"""
import argparse
import json
import os
import re

import boto3
import oracledb


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=os.getenv("ORACLE_HOST"), required=not os.getenv("ORACLE_HOST"))
    parser.add_argument("--master-secret-arn", default=os.getenv("ORACLE_MASTER_SECRET_ARN"), required=not os.getenv("ORACLE_MASTER_SECRET_ARN"))
    parser.add_argument("--user-access-secret-arn", default=os.getenv("USER_ACCESS_DB_SECRET_ARN"), required=not os.getenv("USER_ACCESS_DB_SECRET_ARN"))
    parser.add_argument("--discovery-secret-arn", default=os.getenv("DISCOVERY_DB_SECRET_ARN"), required=not os.getenv("DISCOVERY_DB_SECRET_ARN"))
    parser.add_argument("--integrity-secret-arn", default=os.getenv("INTEGRITY_DB_SECRET_ARN"), required=not os.getenv("INTEGRITY_DB_SECRET_ARN"))
    args = parser.parse_args()
    secrets = boto3.client("secretsmanager")

    def secret(arn):
        return secrets.get_secret_value(SecretId=arn)["SecretString"]

    master = json.loads(secret(args.master_secret_arn))
    passwords = {
        "USER_ACCESS": secret(args.user_access_secret_arn),
        "DISCOVERY": secret(args.discovery_secret_arn),
        "INTEGRITY_SERVICE": secret(args.integrity_secret_arn),
    }
    # CREATE USER accepts a quoted password identifier, not a bind variable.
    for password in passwords.values():
        if not re.fullmatch(r"[A-Za-z0-9]{32}", password):
            raise ValueError("Unexpected schema password format")
    with oracledb.connect(
        user=master["username"],
        password=master["password"],
        dsn=f"{args.host}:1521/ORCL",
    ) as db:
        with db.cursor() as cursor:
            for username, password in passwords.items():
                cursor.execute("SELECT COUNT(*) FROM ALL_USERS WHERE USERNAME = :name", name=username)
                if cursor.fetchone()[0] == 0:
                    cursor.execute(
                        f'CREATE USER {username} IDENTIFIED BY "{password}" '
                        "DEFAULT TABLESPACE USERS QUOTA 512M ON USERS"
                    )
                cursor.execute(f"GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO {username}")
            cursor.execute(
                "SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER = 'INTEGRITY_SERVICE' "
                "AND TABLE_NAME = 'INTEGRITY_VALIDATION_LOG'"
            )
            if cursor.fetchone()[0] == 0:
                cursor.execute(
                    """CREATE TABLE INTEGRITY_SERVICE.INTEGRITY_VALIDATION_LOG (
                    VALIDATION_ID NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    BUCKET_NAME VARCHAR2(255) NOT NULL,
                    OBJECT_KEY VARCHAR2(1024) NOT NULL,
                    SOURCE_CHECKSUM VARCHAR2(64) NOT NULL,
                    DESTINATION_CHECKSUM VARCHAR2(64),
                    ALGORITHM VARCHAR2(20) NOT NULL,
                    VALIDATION_STATUS VARCHAR2(20) NOT NULL,
                    ERROR_MESSAGE VARCHAR2(2000),
                    VALIDATED_AT TIMESTAMP NOT NULL)"""
                )
        db.commit()
    print("Fresh Oracle schemas and Integrity table are ready")


if __name__ == "__main__":
    main()
