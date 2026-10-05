# MMIV Transfer Service

Standalone microservice prepared for merging into the AWS setup branch.

## Configuration

- `AWS_REGION` defaults to `ap-southeast-1`.
- `MMIV_BUCKET_NAME` defaults to `media-migration-dev-landing`.
- The service uses the AWS SDK default credential chain, so an EC2 instance profile can provide credentials without storing AWS keys in the container.

## Build

```bash
mvn -B clean package
```

## Run

```bash
java -jar target/transfer-service.jar
```

## Transfer API

```http
POST /api/v1/transfers
Content-Type: application/json

{
  "sourcePath": "/data/media/example.mp4",
  "destinationKey": "AY2026-2027/Semester_1/CS2103/Lectures/example.mp4",
  "contentType": "video/mp4"
}
```

The implementation uses the Strategy Pattern and a factory so additional transfer targets can be added later.
