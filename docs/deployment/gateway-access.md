# Browser access through the API Gateway

[English](gateway-access.md) | [简体中文](gateway-access.zh-CN.md)

After Terraform apply, host preparation, and Docker deployment, open:

- `http://<EC2-public-IP>:8080/` (opens the login page)
- `http://<EC2-public-IP>:8080/pages/home.html`
- `http://<EC2-public-IP>:8080/pages/reports.html`

Terraform permits public IPv4 TCP 8080. Docker Compose publishes api-gateway at
0.0.0.0:8080:8080. Backend service and registry host ports bind to 127.0.0.1;
containers communicate over mmiv-backend. Deployment, rescue and rollback use
the same binding default. API paths remain /api/v1/transfers and /api/v1/files/\*\*.

Gateway validates Bearer JWTs and HttpOnly browser-session cookies. Backend
ports are not public, and owner/admin authorization remains enforced by each
backend. For network-level restriction, replace the security group's
`0.0.0.0/0` ingress CIDR with your permitted client CIDR before applying
Terraform.

SSM port forwarding remains optional:

```bash
bash scripts/cd/open-gateway-tunnel.sh i-YOUR_INSTANCE_ID
```

Then open `http://localhost:8080/` while the session runs. Sign in or create an
account; successful login opens `/pages/home.html`.

The deployed release contains all seven application services. Discovery and User
Access use the staged Oracle configuration; Reporting uses MongoDB; all services
publish audit events through RabbitMQ and discover one another through Eureka.

Folder API: POST /api/v1/transfers/folder with matching files and relativePaths.
Limit: 500 files and 100 MiB total. Transfer invokes Integrity after upload and requires confirmed integrity success. Redis is not part of the active deployment.

Gateway pages and Transfer file APIs require login. Reporting routes go directly
from Gateway to Reporting and do not depend on Transfer. See
[login setup](login-access.md) for cookie sessions, authenticated CD checks and
signup.
