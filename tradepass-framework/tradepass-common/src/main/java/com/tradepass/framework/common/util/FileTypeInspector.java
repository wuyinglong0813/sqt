package com.tradepass.framework.common.util;

import com.tradepass.framework.common.exception.BusinessException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class FileTypeInspector {
    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");

    private FileTypeInspector() {
    }

    public static String inspect(byte[] data) {
        if (data == null || data.length == 0) {
            throw new BusinessException("请选择文件");
        }
        if (data.length > MAX_FILE_SIZE) {
            throw new BusinessException("文件不能超过 10MB");
        }
        String image = imageType(data);
        if (image != null) return image;
        if (startsWith(data, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return "application/pdf";
        }
        if (isOleDocument(data)) {
            return "application/msword";
        }
        if (isZip(data)) {
            return inspectOfficeZip(data);
        }
        throw new BusinessException("文件格式不支持");
    }

    public static boolean isImage(String contentType) {
        return IMAGE_TYPES.contains(contentType);
    }

    public static boolean isWord(String contentType) {
        return "application/msword".equals(contentType)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(contentType);
    }

    public static boolean isXlsx(String contentType) {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(contentType);
    }

    public static String sanitizeFileName(String originalName, String contentType) {
        String name = originalName == null ? "" : originalName
                .replace("\\", "_")
                .replace("/", "_")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
        if (name.isBlank()) name = "资料." + extension(contentType);
        if (name.length() > 255) name = name.substring(name.length() - 255);
        return name;
    }

    public static String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/jpeg" -> "jpg";
            case "application/pdf" -> "pdf";
            case "application/msword" -> "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            default -> "bin";
        };
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String imageType(byte[] data) {
        if (data.length >= 3 && unsigned(data[0]) == 0xff && unsigned(data[1]) == 0xd8
                && unsigned(data[2]) == 0xff) return "image/jpeg";
        if (data.length >= 8 && unsigned(data[0]) == 0x89 && data[1] == 'P' && data[2] == 'N'
                && data[3] == 'G' && unsigned(data[4]) == 0x0d && unsigned(data[5]) == 0x0a
                && unsigned(data[6]) == 0x1a && unsigned(data[7]) == 0x0a) return "image/png";
        if (data.length >= 6) {
            String signature = new String(data, 0, 6, StandardCharsets.US_ASCII);
            if ("GIF87a".equals(signature) || "GIF89a".equals(signature)) return "image/gif";
        }
        if (data.length >= 12
                && "RIFF".equals(new String(data, 0, 4, StandardCharsets.US_ASCII))
                && "WEBP".equals(new String(data, 8, 4, StandardCharsets.US_ASCII))) return "image/webp";
        return null;
    }

    private static boolean isOleDocument(byte[] data) {
        byte[] signature = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
                (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};
        return startsWith(data, signature);
    }

    private static boolean isZip(byte[] data) {
        return data.length >= 4 && data[0] == 'P' && data[1] == 'K'
                && data[2] == 3 && data[3] == 4;
    }

    private static String inspectOfficeZip(byte[] data) {
        boolean contentTypes = false;
        boolean word = false;
        boolean excel = false;
        int entries = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null && entries++ < 5000) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if ("[content_types].xml".equals(name)) contentTypes = true;
                if (name.startsWith("word/") && !entry.isDirectory()) word = true;
                if (name.startsWith("xl/") && !entry.isDirectory()) excel = true;
                if (contentTypes && (word || excel)) break;
            }
        } catch (Exception exception) {
            throw new BusinessException("Office 文件无法读取");
        }
        if (contentTypes && word) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (contentTypes && excel) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        throw new BusinessException("仅支持 DOCX 或 XLSX 格式的 Office 文件");
    }

    private static boolean startsWith(byte[] data, byte[] signature) {
        if (data.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if (data[i] != signature[i]) return false;
        }
        return true;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
