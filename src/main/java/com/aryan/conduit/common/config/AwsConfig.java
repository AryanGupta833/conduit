package com.aryan.conduit.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class AwsConfig {

    @Bean
    public S3Client s3Client(
            @Value("${aws.region:ap-south-1}") String region) {

        return S3Client.builder()
                .region(Region.of(region))
                .build();
    }
}