# Retired producer

The SQS producer has been replaced with RabbitMQ. See [the integration guide](../../integration/services.md). Existing SQS infrastructure is retained to preserve queued historical records; running services do not publish to it.
