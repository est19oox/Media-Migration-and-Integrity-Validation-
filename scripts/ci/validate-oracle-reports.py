#!/usr/bin/env python3
"""Require both Oracle suites to execute successfully, rather than merely discover tests."""
import sys
from pathlib import Path
import xml.etree.ElementTree as ET


def validate(directory):
    total = 0
    for suite in ("controller.AuthLoginIT", "repository.OracleUserRepositoryIT"):
        report = directory / ("TEST-mmiv.user_access_service." + suite + ".xml")
        root = ET.parse(report).getroot()
        tests, failures, errors, skipped = (int(root.get(key, 0)) for key in
                                           ("tests", "failures", "errors", "skipped"))
        if tests <= 0 or skipped or failures or errors:
            raise ValueError(f"{suite}: expected executed passing tests; tests={tests}, failures={failures}, errors={errors}, skipped={skipped}")
        total += tests
    print(f"PASS: Both Oracle integration suites executed; {total} tests passed.")


if __name__ == "__main__":
    try:
        validate(Path(sys.argv[1]))
    except (OSError, ValueError, ET.ParseError) as error:
        sys.exit(f"FAIL: Oracle integration evidence invalid: {error}")
