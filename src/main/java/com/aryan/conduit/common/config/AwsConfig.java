package com.aryan.conduit.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import java.time.Duration;

@Configuration
public class AwsConfig {

    @Bean
    public S3Client s3Client(
            @Value("${aws.region:ap-south-1}") String region) {

        return S3Client.builder()
                .region(Region.of(region))
                .build();
    }

    private ClientOverrideConfiguration boundedClientConfiguration() {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(60))
                .apiCallAttemptTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Bean public SnsClient snsClient(@Value("${aws.region:ap-south-1}") String region) {
        return SnsClient.builder().region(Region.of(region)).overrideConfiguration(boundedClientConfiguration()).build();
    }
    @Bean public SqsClient sqsClient(@Value("${aws.region:ap-south-1}") String region) {
        return SqsClient.builder().region(Region.of(region)).overrideConfiguration(boundedClientConfiguration()).build();
    }
    @Bean public LambdaClient lambdaClient(@Value("${aws.region:ap-south-1}") String region) {
        return LambdaClient.builder().region(Region.of(region)).overrideConfiguration(boundedClientConfiguration()).build();
    }
    @Bean public DynamoDbClient dynamoDbClient(@Value("${aws.region:ap-south-1}") String region) {
        return DynamoDbClient.builder().region(Region.of(region)).overrideConfiguration(boundedClientConfiguration()).build();
    }
}
