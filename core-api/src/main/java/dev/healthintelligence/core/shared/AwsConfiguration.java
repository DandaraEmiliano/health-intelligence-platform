package dev.healthintelligence.core.shared;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AWS SDK clients with the default credentials chain. Endpoints are overridden only when set, for
 * the local emulators (ADR 0012); path-style access is what S3Mock understands.
 */
@Configuration(proxyBeanMethods = false)
class AwsConfiguration {

    @Bean(destroyMethod = "close")
    S3Client s3Client(@Value("${hip.aws.region}") String region, @Value("${hip.aws.s3-endpoint:}") String endpoint) {
        var builder = S3Client.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(@Value("${hip.aws.region}") String region, @Value("${hip.aws.s3-endpoint:}") String endpoint) {
        var builder = S3Presigner.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    SqsClient sqsClient(@Value("${hip.aws.region}") String region, @Value("${hip.aws.sqs-endpoint:}") String endpoint) {
        var builder = SqsClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
