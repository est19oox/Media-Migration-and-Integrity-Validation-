#!/usr/bin/env python3
"""Remove verification credentials and every refreshed JWT from scan evidence."""
import os
from pathlib import Path
import sys

values = {os.environ.get(name, '') for name in
          ('API_TEST_TOKEN', 'USER_ACCESS_TEST_PASSWORD', 'USER_ACCESS_TEST_USERNAME')}
history = os.environ.get('MMIV_VERIFICATION_TOKEN_FILE')
if history:
    values.update(Path(history).read_text().splitlines())
values.discard('')
for path in Path(sys.argv[1]).rglob('*'):
    if path.is_file():
        data = path.read_bytes()
        cleaned = data
        for value in sorted(values, key=len, reverse=True):
            cleaned = cleaned.replace(value.encode(), b'[REDACTED]')
        if cleaned != data:
            path.write_bytes(cleaned)
