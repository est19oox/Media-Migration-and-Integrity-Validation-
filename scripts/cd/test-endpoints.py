#!/usr/bin/env python3
"""Config-driven HTTP integration tests; uses only Python's standard library."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import time
import uuid
import base64
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


def expand(value, variables):
    if isinstance(value, str):
        def replace(match):
            name = match.group(1)
            if name not in variables or not variables[name]:
                raise ValueError(f"Missing variable: {name}")
            return variables[name]
        return re.sub(r"\$\{([A-Z_][A-Z0-9_]*)\}", replace, value)
    if isinstance(value, dict):
        return {key: expand(item, variables) for key, item in value.items()}
    if isinstance(value, list):
        return [expand(item, variables) for item in value]
    return value


def field(body, dotted_name):
    value = body
    for part in dotted_name.split("."):
        if isinstance(value, dict) and part in value:
            value = value[part]
        elif isinstance(value, list) and part.isdecimal() and int(part) < len(value):
            value = value[int(part)]
        else:
            raise ValueError(f"Missing JSON field: {dotted_name}")
    return value


def multipart_body(parts):
    boundary = "mmiv-" + uuid.uuid4().hex
    chunks = []
    for part in parts:
        name = part["name"]
        filename = part.get("filename")
        if any(c in name + (filename or "") for c in '\r\n"'):
            raise ValueError("Invalid multipart field name or filename")
        disposition = f'Content-Disposition: form-data; name="{name}"'
        if filename is not None:
            disposition += f'; filename="{filename}"'
        headers = disposition + "\r\n"
        if filename is not None:
            media_type = part.get('content_type', 'application/octet-stream')
            if '\r' in media_type or '\n' in media_type:
                raise ValueError("Invalid multipart content type")
            headers += 'Content-Type: ' + media_type + '\r\n'
        content = base64.b64decode(part['base64'], validate=True) if 'base64' in part else part.get('content', '').encode()
        chunks.append(("--" + boundary + "\r\n" + headers + "\r\n").encode() + content + b"\r\n")
    chunks.append(("--" + boundary + "--\r\n").encode())
    return b''.join(chunks), 'multipart/form-data; boundary=' + boundary


def run_case(case, base_url, token):
    path = case["path"]
    if not path.startswith("/") or path.startswith("//"):
        raise ValueError("Test paths must be relative to the service root")
    data = None
    if "body" in case:
        data = json.dumps(case["body"]).encode()
    elif "raw_body" in case:
        data = case["raw_body"].encode()
    headers = {"Content-Type": "application/json", **case.get("headers", {})}
    if "multipart" in case:
        data, headers["Content-Type"] = multipart_body(case["multipart"])
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(base_url + path, data=data,
                      method=case.get("method", "GET"), headers=headers)
    try:
        with urlopen(request, timeout=case.get("timeout", 60)) as response:
            status, content = response.status, response.read()
            response_headers = response.headers
    except HTTPError as error:
        status, content = error.code, error.read()
        response_headers = error.headers
    except (URLError, TimeoutError, OSError) as error:
        raise ValueError(f"HTTP request failed: {type(error).__name__}") from error
    # Retain only configured diagnostic fields, even when an assertion fails.
    # Never record arbitrary response bodies or request authentication headers.
    case["_http_status"] = status
    body = None
    if any(key.startswith("json_") for key in case) or case.get("save_fields") or case.get("report_fields"):
        try:
            body = json.loads(content)
        except ValueError:
            pass
    reported = {}
    for name in case.get("report_fields", []):
        try:
            reported[name] = field(body, name)
        except ValueError:
            continue
    case["_reported_fields"] = reported
    if status != case["status"]:
        raise ValueError(f"Expected HTTP {case['status']}, received {status}")
    for name, expected in case.get("header_equals", {}).items():
        if response_headers.get(name) != expected:
            raise ValueError(f"Unexpected response header: {name}")
    if "body_equals" in case and content != case["body_equals"].encode():
        raise ValueError("Response bytes do not match the uploaded fixture")
    if not any(key.startswith("json_") for key in case) and not case.get("save_fields") and not case.get("report_fields"):
        return status
    if body is None:
        raise ValueError("Response is not valid JSON")
    if case.get("json_object") and not isinstance(body, dict):
        raise ValueError("Expected a JSON object")
    for name in case.get("json_required", []):
        field(body, name)
    for name in case.get("json_nonempty", []):
        if not field(body, name):
            raise ValueError(f"JSON field is empty: {name}")
    for name, expected in case.get("json_equals", {}).items():
        actual = field(body, name)
        if actual != expected or (isinstance(actual, bool) != isinstance(expected, bool)):
            raise ValueError(f"Unexpected value for JSON field: {name}; expected {expected!r}, received {actual!r}")
    for name, minimum in case.get("json_min", {}).items():
        value = field(body, name)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or value < minimum:
            raise ValueError(f"JSON field below minimum: {name}")
    case["_saved_vars"] = {name: str(field(body, dotted)) for name, dotted in case.get("save_fields", {}).items()}
    return status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", required=True)
    parser.add_argument("--base-url", required=True)
    default_config = Path(__file__).resolve().parents[2] / "tests/api/endpoints.json"
    parser.add_argument("--config", type=Path, default=default_config)
    parser.add_argument("--report", type=Path,
                        default=Path(".ci/reports/endpoint-tests.json"))
    parser.add_argument("--require-no-skips", action="store_true")
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")
    parsed = urlsplit(base_url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.query or parsed.fragment:
        parser.error("--base-url must be an HTTP(S) service root")
    config = json.loads(args.config.read_text())
    if args.service not in config["services"]:
        parser.error(f"No tests configured for {args.service}")
    run_id = uuid.uuid4().hex
    variables = {**os.environ, "RUN_ID": run_id,
                 "TEST_OBJECT_KEY": f"endpoint-tests/{run_id}.txt"}
    cases = config.get("common", []) + config["services"][args.service]
    if not cases:
        parser.error("No endpoint tests configured")
    results = []
    for original in cases:
        started = time.monotonic()
        result = {"name": original["name"], "method": original.get("method", "GET"),
                  "path": original["path"]}
        missing = [name for name in original.get("requires_env", []) if not os.getenv(name)]
        if missing:
            result.update(status="SKIP", detail="Set " + ", ".join(missing))
        else:
            case = {}
            try:
                case = expand(original, variables)
                result["path"] = case["path"]
                status = run_case(case, base_url, os.getenv("API_TEST_TOKEN", ""))
                result.update(status="PASS", http_status=status)
                variables.update(case.get("_saved_vars", {}))
                if original["name"] == "successful S3 transfer":
                    result["test_object_key"] = variables["TEST_OBJECT_KEY"]
            except (ValueError, TypeError, KeyError) as error:
                result.update(status="FAIL", detail=str(error))
            if "_http_status" in case:
                result["http_status"] = case["_http_status"]
            if case.get("_reported_fields"):
                result["response"] = case["_reported_fields"]
        result["elapsed_seconds"] = round(time.monotonic() - started, 3)
        results.append(result)
        print(f"{result['status']}: {args.service} / {result['name']} "
              f"({result['elapsed_seconds']}s) {result.get('detail', '')}", flush=True)
    summary = {status: sum(item["status"] == status for item in results)
               for status in ("PASS", "FAIL", "SKIP")}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps({"service": args.service, "summary": summary,
                                      "results": results}, indent=2) + "\n")
    print(f"Summary: {summary}; report: {args.report}")
    return 1 if summary["FAIL"] or (args.require_no_skips and summary["SKIP"]) else 0


if __name__ == "__main__":
    sys.exit(main())
