package com.tradepass.module.file.framework.file.core.client;

import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.auth.COSCredentialsProvider;

import java.time.Instant;
import java.util.function.LongSupplier;

class CloudBaseCosCredentialsProvider implements COSCredentialsProvider {
    private final CloudBaseOpenApiClient openApiClient;
    private final long refreshSkewSeconds;
    private final LongSupplier epochSeconds;

    private volatile CachedCredential cached;

    CloudBaseCosCredentialsProvider(CloudBaseOpenApiClient openApiClient, long refreshSkewSeconds) {
        this(openApiClient, refreshSkewSeconds, () -> Instant.now().getEpochSecond());
    }

    CloudBaseCosCredentialsProvider(CloudBaseOpenApiClient openApiClient, long refreshSkewSeconds,
                                    LongSupplier epochSeconds) {
        this.openApiClient = openApiClient;
        this.refreshSkewSeconds = Math.max(15, refreshSkewSeconds);
        this.epochSeconds = epochSeconds;
    }

    @Override
    public COSCredentials getCredentials() {
        CachedCredential current = cached;
        if (current == null || expiresSoon(current)) {
            synchronized (this) {
                current = cached;
                if (current == null || expiresSoon(current)) {
                    current = fetchCredential();
                    cached = current;
                }
            }
        }
        return current.credentials();
    }

    @Override
    public synchronized void refresh() {
        cached = fetchCredential();
    }

    private boolean expiresSoon(CachedCredential credential) {
        return credential.expiredTime() <= epochSeconds.getAsLong() + refreshSkewSeconds;
    }

    private CachedCredential fetchCredential() {
        CloudBaseOpenApiClient.TemporaryCredential value = openApiClient.getTemporaryCredential();
        BasicSessionCredentials credentials = new BasicSessionCredentials(
                value.secretId(), value.secretKey(), value.token());
        return new CachedCredential(credentials, value.expiredTime());
    }

    private record CachedCredential(BasicSessionCredentials credentials, long expiredTime) {
    }
}
