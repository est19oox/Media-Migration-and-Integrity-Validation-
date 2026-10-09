# Browser access through the Gateway

Authenticate AWS CLI with an identity permitted to start an SSM port-forwarding session on the MMIV instance, and install the AWS Session Manager plugin.

Bash:
~~~bash
bash scripts/cd/open-gateway-tunnel.sh i-YOUR_INSTANCE_ID
~~~

PowerShell (replace the instance ID):
~~~powershell
aws ssm start-session --region ap-southeast-1 --target i-YOUR_INSTANCE_ID --document-name AWS-StartPortForwardingSession --parameters "portNumber=8080,localPortNumber=8080"
~~~

Keep the command running and open http://localhost:8080/upload.html. File and API requests go through Gateway to Transfer. Port 8080 must be free locally. This is authenticated developer access, not a public website. EC2 retains no public inbound rules. Gateway and backend host ports bind to 127.0.0.1; containers communicate over mmiv-backend independently of Redis.

Folder API: POST /api/v1/transfers/folder, multipart fields files and relativePaths, up to 500 files and 100 MiB total. Supported extensions: png, jpg, jpeg, mp4, txt, pdf, ppt, pptx, doc, docx. Empty folders are not uploaded. Office files download for viewing.

Redis definitions and invocation are commented out. Existing Redis containers are not removed. Restore the commented playbook, role and settings deliberately if Redis is needed later.
