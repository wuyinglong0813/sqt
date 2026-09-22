package com.tradepass.module.file.framework.file.core.client;

import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.auth.COSSessionCredentials;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudBaseCosCredentialsProviderTest {

    @Test
    void cachesCredentialsAndRefreshesBeforeExpiration() {
        CloudBaseOpenApiClient openApi = mock(CloudBaseOpenApiClient.class);
        AtomicLong now = new AtomicLong(1000);
        when(openApi.getTemporaryCredential())
                .thenReturn(new CloudBaseOpenApiClient.TemporaryCredential("id-1", "key-1", "token-1", 1200))
                .thenReturn(new CloudBaseOpenApiClient.TemporaryCredential("id-2", "key-2", "token-2", 2000));
        CloudBaseCosCredentialsProvider provider = new CloudBaseCosCredentialsProvider(
                openApi, 60, now::get);

        COSCredentials first = provider.getCredentials();
        COSCredentials cached = provider.getCredentials();
        now.set(1150);
        COSCredentials refreshed = provider.getCredentials();

        assertThat(first.getCOSAccessKeyId()).isEqualTo("id-1");
        assertThat(cached.getCOSAccessKeyId()).isEqualTo("id-1");
        assertThat(((COSSessionCredentials) first).getSessionToken()).isEqualTo("token-1");
        assertThat(refreshed.getCOSAccessKeyId()).isEqualTo("id-2");
        verify(openApi, times(2)).getTemporaryCredential();
    }
}
