# MMIV service failover demonstration (secondary EC2 only)

This deliberately **does not change or use the primary EC2**. It runs two tiny nginx replicas on the secondary t3.medium (`i-0dda783e05c2d95c8`) on ports 8091 and 8092. They can be registered as separate targets (same instance ID, different ports) behind a future ALB. This demonstrates **container/service failover on one host**, NOT host, Availability Zone, database, or full MMIV failover.

## Start on secondary EC2 using SSM

```bash
git clone --branch feature/secondary-ec2-isolated https://github.com/est19oox/Media-Migration-and-Integrity-Validation-.git mmiv-ha
cd mmiv-ha/infrastructure/secondary-ec2/ha-demo
sudo docker compose up -d
curl -fsS http://127.0.0.1:8091/
curl -fsS http://127.0.0.1:8092/
```

If `git` is unavailable, install it with `sudo dnf install -y git`.

## Controlled failure test

After configuring an ALB with two registered healthy instance targets on ports 8091 and 8092, stop one replica:

```bash
sudo docker stop mmiv-ha-demo-a
```

Wait for the ALB health checks to mark target A unhealthy; ALB requests should then reach B. Restore A with:

```bash
sudo docker start mmiv-ha-demo-a
```

Use `sudo docker compose down` to clean up the demo containers when finished. Do not use `down -v` against the primary application stack.

## Networking

The current secondary security group has no inbound rules. A future ALB will need its own security group and inbound TCP 8091/8092 **from the ALB security group to the secondary only**. Do not open these demo ports to the public internet.

The existing pending secondary Terraform outbound-rule change is unrelated to this demo; do not apply it merely to run these containers.
