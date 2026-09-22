package com.tradepass.module.file.api.file;

public interface ObjectStorageService {
    boolean isEnabled();

    StoredObject putImmutable(String objectKey, byte[] data, String contentType, String sha256);

    byte[] get(ObjectReference reference);

    record StoredObject(String provider, String bucket, String objectKey, String versionId,
                        String etag, String encryptionAlgorithm, long fileSize, String sha256) {
        public ObjectReference reference() {
            return new ObjectReference(bucket, objectKey, versionId, fileSize, sha256);
        }
    }

    record ObjectReference(String bucket, String objectKey, String versionId,
                           Long fileSize, String sha256) {
    }
}
