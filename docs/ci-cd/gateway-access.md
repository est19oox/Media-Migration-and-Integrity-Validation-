# Browser access through the API Gateway

After Terraform apply, host preparation, and Docker deployment, open:

- http://<EC2-public-IP>:8080/upload.html
- http://<EC2-public-IP>:8080/files.html
- http://<EC2-public-IP>:8080/ (opens the login page)

Terraform permits public IPv4 TCP 8080. Docker Compose publishes api-gateway at
0.0.0.0:8080:8080. Backend service and registry host ports bind to 127.0.0.1;
containers communicate over mmiv-backend. Deployment, rescue and rollback use
the same binding default. API paths remain /api/v1/transfers and /api/v1/files/**.

The gateway currently has no authentication; this configuration makes its
upload and file APIs reachable by anyone with network access to port 8080.
For restricted access, replace the security group's 0.0.0.0/0 ingress CIDR with
your permitted client CIDR before applying Terraform.

SSM port forwarding remains optional:

```bash
bash scripts/cd/open-gateway-tunnel.sh i-YOUR_INSTANCE_ID
```

Then open http://localhost:8080/ while the session runs. Sign in or create an account; successful login opens /upload.html.

The real Discovery Service from main is preserved, including Oracle configuration,
CSV processing, repositories and tests. Its automatic CD entry is commented out
because its DB_URL, DB_USERNAME, DB_PASSWORD and DISCOVERY_ROOT must be supplied.
It currently has no Eureka client, so its gateway route is not functional yet.
For local Compose, enable it explicitly with --profile discovery after supplying
the database settings and DISCOVERY_MEDIA_PATH. Other services use the registry.

Folder API: POST /api/v1/transfers/folder with matching files and relativePaths.
Limit: 500 files and 100 MiB total. Transfer invokes Integrity after upload and requires confirmed integrity success. Redis remains disabled as in the uploaded transfer project.

Transfer pages and file APIs now require login. See [login setup](login-access.md) for cookie sessions, authenticated CD checks and signup.
