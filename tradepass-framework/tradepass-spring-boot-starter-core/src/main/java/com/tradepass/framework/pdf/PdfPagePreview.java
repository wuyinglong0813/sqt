package com.tradepass.framework.pdf;

import com.tradepass.framework.cache.core.BoundedBinaryCache;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.FileChunkDataPayload;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;

/** Actual PDF pages, rendered only when requested. Callers must authorize the
 * underlying source on every call, including cache hits. */
public final class PdfPagePreview {
    public static final int MAX_PAGES = 500;
    public static final int MAX_PDF_BYTES = 32 * 1024 * 1024;
    private static final int MAX_CHUNK_BYTES = 640 * 1024;
    private static final long MAX_PAGE_PIXELS = 4_000_000L;
    private static final int MAX_EDGE = 2400;
    private final BoundedBinaryCache pdfs = new BoundedBinaryCache(Duration.ofMinutes(10), 32L * 1024 * 1024);
    private final BoundedBinaryCache images = new BoundedBinaryCache(Duration.ofMinutes(10), 64L * 1024 * 1024);

    public record Source(String version, Supplier<byte[]> load) {}
    public record Manifest(int totalPages, String previewKey) {}

    public Manifest manifest(Source source) {
        byte[] pdf = pdf(source);
        try (PDDocument document = open(pdf)) {
            return new Manifest(pageCount(document), source.version());
        } catch (IOException exception) {
            throw unreadable();
        }
    }

    public FileChunkDataPayload chunk(Source source, int page, String previewKey, long offset, int size) {
        if (page < 1 || page > MAX_PAGES) throw new BusinessException("PDF 预览页码不正确");
        if (offset < 0 || size <= 0 || size > MAX_CHUNK_BYTES) throw new BusinessException("文件分片参数不正确");
        if (previewKey == null || !previewKey.equals(source.version())) {
            throw new BusinessException(409, "PDF 内容已更新，请重新加载预览");
        }
        byte[] image = images.get(source.version() + ":" + page, () -> render(pdf(source), page));
        if (offset >= image.length) throw new BusinessException("文件分片位置超出范围");
        int start = (int) offset;
        int end = (int) Math.min(image.length, offset + size);
        return FileChunkDataPayload.of("第" + page + "页.png", "image/png",
                java.util.Arrays.copyOfRange(image, start, end), offset, image.length, end == image.length);
    }

    public static String fingerprint(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("pdf-preview-v1".getBytes(StandardCharsets.UTF_8));
            for (byte[] part : parts) {
                byte[] value = part == null ? new byte[0] : part;
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] pdf(Source source) {
        if (source == null || source.version() == null || !source.version().matches("[a-f0-9]{64}")) {
            throw new BusinessException("PDF 预览版本不正确");
        }
        return pdfs.get(source.version(), () -> {
            byte[] data = source.load().get();
            if (data == null || data.length < 5 || data.length > MAX_PDF_BYTES) {
                throw new BusinessException("PDF 内容为空或过大，请查看原文件");
            }
            return data;
        });
    }

    private PDDocument open(byte[] pdf) throws IOException {
        PDDocument document = Loader.loadPDF(pdf);
        if (document.isEncrypted()) {
            document.close();
            throw new BusinessException("加密 PDF 请下载后查看");
        }
        return document;
    }

    private int pageCount(PDDocument document) {
        int count = document.getNumberOfPages();
        if (count < 1 || count > MAX_PAGES) throw new BusinessException("PDF 页数超出预览范围，请查看原文件");
        return count;
    }

    private byte[] render(byte[] pdf, int page) {
        try (PDDocument document = open(pdf)) {
            if (page > pageCount(document)) throw new BusinessException("PDF 预览页码超出范围");
            PDRectangle box = document.getPage(page - 1).getCropBox();
            double width = box.getWidth(), height = box.getHeight();
            if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) throw unreadable();
            double scale = Math.min(2, Math.min(MAX_EDGE / Math.max(width, height),
                    Math.sqrt(MAX_PAGE_PIXELS / (width * height))));
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            BufferedImage image = renderer.renderImage(page - 1, (float) scale, ImageType.RGB);
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (!ImageIO.write(image, "png", output)) throw unreadable();
                return output.toByteArray();
            } finally {
                image.flush();
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw unreadable();
        }
    }

    private BusinessException unreadable() {
        return new BusinessException("PDF 预览生成失败，请查看原文件或重试");
    }
}
