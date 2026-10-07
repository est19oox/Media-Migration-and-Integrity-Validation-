# Generic endpoint tests

One Python script runs the shared tests and the selected service's tests from endpoints.json. No extra Python dependencies are required. These are HTTP integration tests, separate from ZAP DAST and Maven unit tests.

Run through an open SSM tunnel:

```bash
python3 scripts/cd/test-endpoints.py --service transfer-service --base-url http://127.0.0.1:18081
```

Run directly from EC2 by replacing the base URL with http://127.0.0.1:8081. Other service ports are integrity 8082, user access 8083, reporting 8084, discovery 8085. The CD workflow runs these tests after tunnel readiness and before ZAP. A failed assertion fails the step and uses the existing rollback path. The endpoint-tests.json result is uploaded with deployment evidence.

The only common case is GET /actuator/health. It requires HTTP 200 and JSON status UP for each selected service. Liveness, readiness, info and Actuator links are not tested. All five services must expose /actuator/health. The four non-transfer services currently have no business controllers. Add their business test cases to the corresponding services entry when implemented; the script does not automatically discover new endpoints. Do not put authentication secrets in endpoints.json. Optional API_TEST_TOKEN supplies an Authorization Bearer header at runtime.

Transfer cases include POST validation errors and malformed JSON. The valid transfer case requires TRANSFER_TEST_SOURCE. Without it, that case is explicitly SKIP; the others still run. To require every configured case, pass --require-no-skips when invoking the test script.

Prepare a fixture once using Session Manager on EC2:

```bash
sudo mkdir -p /var/lib/mmiv/media
printf 'endpoint test\n' | sudo tee /var/lib/mmiv/media/endpoint-test.txt
```

Set GitHub repository Settings -> Secrets and variables -> Actions -> Variables -> TRANSFER_TEST_SOURCE to /media/endpoint-test.txt. The workflow passes it to the runner. The successful test performs a real transfer and creates a uniquely named object under endpoint-tests/ in the configured bucket. It checks the API response, not the downloaded object's bytes. Arrange cleanup/lifecycle for those test objects or remove their reported keys after reviewing the run. Do not use production media fixtures.

Example test case to add inside a service's array (replace the route with an implemented route):

```json
{
  "name": "list records",
  "method": "GET",
  "path": "/api/v1/records",
  "status": 200
}
```

Use body for JSON POST/PUT requests or raw_body for malformed-body tests. json_equals checks dotted JSON fields, json_required checks field presence, json_nonempty checks nonempty values, and json_min checks numeric minima. ${RUN_ID}, ${TEST_OBJECT_KEY}, and environment variables can be substituted in strings; set requires_env for optional fixture-dependent cases. Each service's tests are independent from the other services.

The existing ZAP baseline still targets /actuator/health. These functional tests extend route and input coverage but do not automatically extend ZAP's security-scan scope; use an OpenAPI/API scan for that.
