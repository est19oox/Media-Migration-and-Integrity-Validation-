#!/usr/bin/env python3
"""Remove the current short-lived verification token from text scan evidence."""
import os
from pathlib import Path
import sys

token = os.environ.get("API_TEST_TOKEN", "")
if token:
    for path in Path(sys.argv[1]).rglob("*"):
        if path.is_file():
            data = path.read_bytes()
            if token.encode() in data:
                path.write_bytes(data.replace(token.encode(), b"[REDACTED]"))
