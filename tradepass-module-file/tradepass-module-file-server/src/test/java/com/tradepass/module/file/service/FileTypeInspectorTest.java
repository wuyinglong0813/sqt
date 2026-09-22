package com.tradepass.module.file.service;
import com.tradepass.framework.common.util.FileTypeInspector;

import com.tradepass.framework.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileTypeInspectorTest {
    @Test
    void detectsPdfAndImagesByContent() {
        assertThat(FileTypeInspector.inspect("%PDF-1.7\n".getBytes()))
                .isEqualTo("application/pdf");
        assertThat(FileTypeInspector.inspect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01}))
                .isEqualTo("image/jpeg");
        assertThat(FileTypeInspector.inspect(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}))
                .isEqualTo("image/png");
        assertThat(FileTypeInspector.inspect("GIF87a".getBytes())).isEqualTo("image/gif");
        assertThat(FileTypeInspector.inspect("GIF89a".getBytes())).isEqualTo("image/gif");
        assertThat(FileTypeInspector.inspect(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}))
                .isEqualTo("image/webp");
        assertThat(FileTypeInspector.inspect(new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
                (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1}))
                .isEqualTo("application/msword");
    }

    @Test
    void distinguishesDocxAndXlsxPackages() throws Exception {
        assertThat(FileTypeInspector.inspect(ooxml("word/document.xml")))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(FileTypeInspector.inspect(ooxml("xl/workbook.xml")))
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    void rejectsUnknownOrEmptyFiles() throws Exception {
        assertThatThrownBy(() -> FileTypeInspector.inspect(new byte[0]))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> FileTypeInspector.inspect("plain text".getBytes()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> FileTypeInspector.inspect(new byte[(int) FileTypeInspector.MAX_FILE_SIZE + 1]))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文件不能超过 10MB");
        assertThatThrownBy(() -> FileTypeInspector.inspect(ooxml("misc/data.xml")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("仅支持 DOCX 或 XLSX 格式的 Office 文件");
    }

    @Test
    void sanitizesNamesAndExposesStableTypeHelpers() {
        assertThat(FileTypeInspector.sanitizeFileName(null, "application/pdf")).isEqualTo("资料.pdf");
        assertThat(FileTypeInspector.sanitizeFileName("../a\\b\r\n.pdf", "application/pdf"))
                .isEqualTo(".._a_b.pdf");
        assertThat(FileTypeInspector.sanitizeFileName("x".repeat(300), "application/pdf")).hasSize(255);
        assertThat(FileTypeInspector.isImage("image/jpeg")).isTrue();
        assertThat(FileTypeInspector.isImage("application/pdf")).isFalse();
        assertThat(FileTypeInspector.isWord("application/msword")).isTrue();
        assertThat(FileTypeInspector.isWord("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .isTrue();
        assertThat(FileTypeInspector.isWord("application/pdf")).isFalse();
        assertThat(FileTypeInspector.isXlsx("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .isTrue();
        assertThat(FileTypeInspector.sha256("abc".getBytes())).hasSize(64);

        assertThat(List.of(
                FileTypeInspector.extension("image/png"),
                FileTypeInspector.extension("image/gif"),
                FileTypeInspector.extension("image/webp"),
                FileTypeInspector.extension("image/jpeg"),
                FileTypeInspector.extension("application/pdf"),
                FileTypeInspector.extension("application/msword"),
                FileTypeInspector.extension("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                FileTypeInspector.extension("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                FileTypeInspector.extension("unknown")
        )).containsExactly("png", "gif", "webp", "jpg", "pdf", "doc", "docx", "xlsx", "bin");
    }

    private byte[] ooxml(String partName) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(partName));
            zip.write("<root/>".getBytes());
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
