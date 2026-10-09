#!/usr/bin/env python3
"""Refuse an EC2 deletion/replacement, including plans from missing state."""
import json
import sys

plan = json.load(sys.stdin)
for change in plan.get("resource_changes", []):
    if change["type"] == "aws_instance" and "delete" in change["change"]["actions"]:
        sys.exit(f"Refusing EC2 deletion/replacement: {change['address']}")
print("EC2 plan preserves existing instances.")
