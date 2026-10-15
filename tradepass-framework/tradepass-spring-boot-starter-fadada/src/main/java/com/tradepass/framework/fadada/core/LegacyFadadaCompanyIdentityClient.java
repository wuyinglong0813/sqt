package com.tradepass.framework.fadada.core;

import com.fasc.open.api.constants.RequestConstants;
import com.fasc.open.api.stratey.DefaultJsonStrategy;
import com.fasc.open.api.utils.crypt.FddCryptUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Isolated, read-only 5.0 identity lookup. Other SDK calls retain their normal 5.1 headers. */
final class LegacyFadadaCompanyIdentityClient {
    private static final Logger log = LoggerFactory.getLogger(LegacyFadadaCompanyIdentityClient.class);
    private static final String FAILURE = "企业历史实名信息暂不可用，请稍后重试";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final String appId;
    private final String appSecret;
    private final URI endpoint;
    private final FadadaAccessTokenProvider tokens;
    private final Transport transport;
    private final LongSupplier clock;
    private final Supplier<String> nonce;
    private final ObjectMapper json = new ObjectMapper();

    LegacyFadadaCompanyIdentityClient(FadadaProperties properties, FadadaAccessTokenProvider tokens) {
        this(properties, tokens, new JdkTransport(), System::currentTimeMillis,
                () -> UUID.randomUUID().toString().replace("-", ""));
    }

    LegacyFadadaCompanyIdentityClient(FadadaProperties properties, FadadaAccessTokenProvider tokens,
                                    Transport transport, LongSupplier clock, Supplier<String> nonce) {
        this.appId = properties.getAppId();
        this.appSecret = properties.getAppSecret();
        this.endpoint = endpoint(properties.getServerUrl());
        this.tokens = tokens;
        this.transport = transport;
        this.clock = clock;
        this.nonce = nonce;
    }

    FadadaCompanyGateway.CompanyIdentity getIdentity(String openCorpId) {
        if (!hasText(openCorpId) || !hasText(appId) || !hasText(appSecret) || endpoint == null) throw failure();
        try {
            String submittedToken = tokens.get();
            JsonNode response = exchange(openCorpId, submittedToken);
            if ("100002".equals(scalar(response, "code"))) {
                tokens.invalidate(submittedToken);
                response = exchange(openCorpId, tokens.get());
            }
            String code = scalar(response, "code");
            if (!"100000".equals(code) || !response.path("data").isObject()) {
                log.warn("Legacy enterprise identity lookup rejected: code={}",
                        code != null && code.matches("[0-9]{1,10}") ? code : "unknown");
                throw failure();
            }
            JsonNode data = response.get("data");
            JsonNode company = data.path("corpIdentInfo");
            if (!openCorpId.equals(text(data, "openCorpId"))
                    || !"identified".equalsIgnoreCase(text(data, "corpIdentStatus"))
                    || !hasText(text(data, "operatorType")) || !hasText(text(data, "operatorId"))) throw failure();
            return new FadadaCompanyGateway.CompanyIdentity(text(data, "openCorpId"), text(data, "corpIdentStatus"),
                    text(company, "corpName"), text(company, "corpIdentNo"), text(company, "legalRepName"),
                    text(data, "corpIdentMethod"), scalar(data, "identSubmitTime"), scalar(data, "identSuccessTime"),
                    text(data, "operatorType"), text(data, "operatorId"), true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure();
        } catch (Exception exception) {
            // Provider messages, response bodies and SDK exception causes can contain private data.
            // Expose only a fixed error and never log an exception message or stack trace here.
            throw failure();
        }
    }

    private JsonNode exchange(String openCorpId, String accessToken) throws Exception {
        if (!hasText(accessToken)) throw failure();
        String business = new DefaultJsonStrategy().toJson(Map.of("openCorpId", openCorpId));
        Map<String, String> headers = new HashMap<>();
        headers.put(RequestConstants.APP_ID, appId);
        headers.put(RequestConstants.SIGN_TYPE, "HMAC-SHA256");
        headers.put(RequestConstants.ACCESS_TOKEN, accessToken);
        headers.put(RequestConstants.TIMESTAMP, String.valueOf(clock.getAsLong()));
        headers.put(RequestConstants.NONCE, nonce.get());
        // 5.0 omits API_SUB_VERSION from BOTH the HTTP headers and signature parameters.
        Map<String, String> signed = new HashMap<>(headers);
        signed.put(RequestConstants.DATA_KEY, business);
        headers.put(RequestConstants.SIGN, FddCryptUtil.sign(FddCryptUtil.sortParameters(signed),
                headers.get(RequestConstants.TIMESTAMP), appSecret));
        String form = RequestConstants.DATA_KEY + "=" + URLEncoder.encode(business, StandardCharsets.UTF_8);
        Reply reply = transport.post(new Request(endpoint, Map.copyOf(headers), form));
        if (reply == null || reply.status() != 200 || reply.body() == null
                || reply.body().getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) throw failure();
        JsonNode response = json.readTree(reply.body());
        if (response == null || !response.isObject()) throw failure();
        return response;
    }

    private static URI endpoint(String baseUrl) {
        try {
            URI base = URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                    || base.getRawQuery() != null || base.getRawFragment() != null) return null;
            return URI.create(baseUrl.replaceAll("/+$", "") + "/corp/get-identity-info");
        } catch (RuntimeException exception) { return null; }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() ? null : value.textValue();
    }

    private static String scalar(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || (!value.isTextual() && !value.isIntegralNumber()) ? null : value.asText();
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static BusinessException failure() { return new BusinessException(FAILURE); }

    record Request(URI endpoint, Map<String, String> headers, String form) {
        @Override public String toString() { return "LegacyIdentityRequest[REDACTED]"; }
    }
    record Reply(int status, String body) {
        @Override public String toString() { return "LegacyIdentityReply[status=" + status + ", body=REDACTED]"; }
    }
    @FunctionalInterface
    interface Transport { Reply post(Request request) throws IOException, InterruptedException; }

    static final class JdkTransport implements Transport {
        private final ConnectionFactory connections;
        private final LongSupplier nanoClock;

        JdkTransport() {
            this(endpoint -> (HttpURLConnection) endpoint.toURL().openConnection());
        }

        JdkTransport(ConnectionFactory connections) {
            this(connections, System::nanoTime);
        }

        JdkTransport(ConnectionFactory connections, LongSupplier nanoClock) {
            this.connections = connections;
            this.nanoClock = nanoClock;
        }

        @Override
        public Reply post(Request request) throws IOException {
            long deadline = nanoClock.getAsLong() + REQUEST_TIMEOUT.toNanos();
            HttpURLConnection connection = connections.open(request.endpoint());
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
                connection.setReadTimeout((int) REQUEST_TIMEOUT.toMillis());
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                request.headers().forEach(connection::setRequestProperty);
                byte[] form = request.form().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(form.length);
                try (var output = connection.getOutputStream()) { output.write(form); }
                int status = connection.getResponseCode();
                if (status != 200) return new Reply(status, "");
                try (var body = connection.getInputStream()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    while (true) {
                        long remaining = deadline - nanoClock.getAsLong();
                        if (remaining <= 0) throw new SocketTimeoutException("Legacy identity response deadline exceeded");
                        connection.setReadTimeout((int) Math.max(1, Math.min(REQUEST_TIMEOUT.toMillis(), remaining / 1_000_000)));
                        int count = body.read(buffer, 0, Math.min(buffer.length, MAX_RESPONSE_BYTES + 1 - bytes.size()));
                        if (count < 0) break;
                        bytes.write(buffer, 0, count);
                        if (bytes.size() > MAX_RESPONSE_BYTES) throw new IOException("Legacy identity response exceeded limit");
                    }
                    return new Reply(status, bytes.toString(StandardCharsets.UTF_8));
                }
            } finally {
                connection.disconnect();
            }
        }
    }

    @FunctionalInterface
    interface ConnectionFactory { HttpURLConnection open(URI endpoint) throws IOException; }
}
