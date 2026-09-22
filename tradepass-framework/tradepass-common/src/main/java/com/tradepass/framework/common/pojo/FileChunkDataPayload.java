package com.tradepass.framework.common.pojo;

import java.util.Base64;

public record FileChunkDataPayload(
        String fileName,
        String contentType,
        String contentBase64,
        long offset,
        int length,
        long totalSize,
        boolean eof) {

    public static FileChunkDataPayload of(String fileName, String contentType, byte[] data,
                                          long offset, long totalSize, boolean eof) {
        return new FileChunkDataPayload(fileName, contentType,
                Base64.getEncoder().encodeToString(data), offset, data.length, totalSize, eof);
    }
}
