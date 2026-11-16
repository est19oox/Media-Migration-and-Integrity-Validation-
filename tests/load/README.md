# User Access autoscaling demonstration

`user_access_signup.py` creates temporary verification accounts, then repeatedly
logs in with them for a bounded period. At the end it logs in as each account
and calls the verification-account deletion endpoint. It writes a JSON report
with response counts, throughput, p95 latency, and the number of User Access
instances observed through Eureka. The report contains no credentials.

On the temporary `demo-gateway-load-no-rate-limit` branch, deploy that branch
to ECS, then run this from your own computer using its current CloudFront domain:

```bash
python3 tests/load/user_access_signup.py \
  --gateway-url https://YOUR-DISTRIBUTION.cloudfront.net \
  --minutes 8 --workers 8 --max-accounts 600 \
  --report user-access-signup-report.json
```

The Gateway mode tests the public, end-to-end route. Its report leaves
`observed_instance_count` null because private Eureka cannot be queried from
your computer; use ECS Tasks and CloudWatch to see replica count. The branch
disables the Gateway rate limiter for all routes, so use it only for the short
demo and redeploy the normal branch afterward.

On the normal branch, run the script from a temporary ECS task or host **inside the MMIV VPC** with
the MMIV task security group, Python 3.11 or later, and access to the private
Eureka and User Access ports. The public Gateway rate limit applies to signup;
traffic through that route will not reliably demonstrate User Access scaling.

```bash
python3 tests/load/user_access_signup.py \
  --minutes 8 --workers 8 --max-accounts 600 \
  --report user-access-signup-report.json
```

Start with these bounded settings. Watch **ECS → mmiv-fargate →
user-access-service → Tasks and Auto Scaling** and the service CPU metric in
CloudWatch. The service's current target is 60% CPU with 1–4 tasks. The JSON
report's `observed_instance_count` shows how many distinct healthy User Access
instances Eureka reported while the test ran. Compare throughput and p95 latency
with a separate run held at one task if you need performance evidence. A higher
task count alone does not prove higher throughput.

The script exits with an error if no account was registered or any cleanup
failed. Check `cleanup_failed` before ending the demo. A forcibly terminated
load process cannot run its cleanup. In that case, the account passwords are
lost with the process, so remove any remaining `mmiv_ci_` accounts through the
database administration process or tear down the disposable demo database.

Local contract test:

```bash
python3 tests/load/test_user_access_signup.py
```
