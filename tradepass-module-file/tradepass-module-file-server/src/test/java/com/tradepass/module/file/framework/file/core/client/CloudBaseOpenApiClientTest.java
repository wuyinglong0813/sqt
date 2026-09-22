package com.tradepass.module.file.framework.file.core.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudBaseOpenApiClientTest {

    @Test
    void parsesTemporaryCredentialsAndSignedFileMetadata() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> auth = response(200, """
                {"TmpSecretId":"id","TmpSecretKey":"secret","Token":"token","ExpiredTime":4102444800}
                """);
        HttpResponse<String> metadata = response(200, """
                {"errcode":0,"errmsg":"ok","respdata":{"x_cos_meta_field_strs":["signed-meta"]}}
                """);
        doReturn(auth, metadata).when(httpClient).send(any(HttpRequest.class), any());
        CloudBaseOpenApiClient client = new CloudBaseOpenApiClient(
                new ObjectMapper(), httpClient, Duration.ofSeconds(2));

        CloudBaseOpenApiClient.TemporaryCredential credential = client.getTemporaryCredential();
        String metaId = client.encodeFileMetadata("bucket", "prod/file/test.pdf");

        assertThat(credential.secretId()).isEqualTo("id");
        assertThat(credential.secretKey()).isEqualTo("secret");
        assertThat(credential.token()).isEqualTo("token");
        assertThat(credential.expiredTime()).isEqualTo(4102444800L);
        assertThat(metaId).isEqualTo("signed-meta");
        verify(httpClient, org.mockito.Mockito.times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void rejectsOpenApiErrorsWithoutExposingResponseSecrets() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        doReturn(response(200, "{\"errcode\":48001,\"errmsg\":\"api unauthorized\"}"))
                .when(httpClient).send(any(HttpRequest.class), any());
        CloudBaseOpenApiClient client = new CloudBaseOpenApiClient(
                new ObjectMapper(), httpClient, Duration.ofSeconds(2));

        assertThatThrownBy(client::getTemporaryCredential)
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信云托管对象存储授权失败，请稍后重试");
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
