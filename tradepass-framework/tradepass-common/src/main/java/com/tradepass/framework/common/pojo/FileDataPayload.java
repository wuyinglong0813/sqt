package com.tradepass.framework.common.pojo;

import java.util.Base64;

public record FileDataPayload(String fileName, String contentType, String contentBase64) {
    public static FileDataPayload of(String fileName, String contentType, byte[] data) {
        return new FileDataPayload(fileName, contentType, Base64.getEncoder().encodeToString(data));
    }
}
