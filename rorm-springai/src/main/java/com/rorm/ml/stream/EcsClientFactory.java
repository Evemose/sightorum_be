package com.rorm.ml.stream;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class EcsClientFactory {

    private final ConcurrentMap<String, EcsClient> clients = new ConcurrentHashMap<>();

    public EcsClient forRegion(String region) {
        return clients.computeIfAbsent(region, r ->
            EcsClient.builder()
                .region(Region.of(r))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build());
    }
}
