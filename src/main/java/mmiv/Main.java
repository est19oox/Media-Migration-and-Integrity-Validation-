package mmiv;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

public class Main {

    public static void main(String[] args) {

        String bucketName = System.getenv("MMIV_BUCKET_NAME");

        if (bucketName == null || bucketName.isBlank()) {
            System.err.println("MMIV_BUCKET_NAME is not set.");
            System.exit(1);
        }

        System.out.println("Testing S3 bucket: " + bucketName);

        try (S3Client s3 = S3Client.builder()
                .region(Region.AP_SOUTHEAST_1)
                .build()) {

            ListObjectsV2Response response = s3.listObjectsV2(request -> request.bucket(bucketName));

            System.out.println("Objects found: " + response.keyCount());

            response.contents().forEach(object -> System.out.println("- " + object.key()));

            System.out.println("S3 connection successful.");

        } catch (Exception e) {
            System.err.println("S3 connection failed.");
            e.printStackTrace();
            System.exit(1);
        }
    }
}