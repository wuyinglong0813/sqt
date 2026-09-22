package com.tradepass.framework.fadada.core;

import com.fasc.open.api.v5_1.res.signtask.SignTaskActorGetUrlRes;
import com.tradepass.framework.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SdkFadadaSigningGatewayTest {

    @Test
    void usesMiniProgramEmbedUrlInsteadOfDistributedShortUrl() {
        SignTaskActorGetUrlRes response = new SignTaskActorGetUrlRes();
        response.setActorSignTaskUrl("https://test.fdd9.cn/short-link");
        response.setActorSignTaskEmbedUrl("https://80005620.uat-e.fadada.com/connect?ticket=one-time");

        assertThat(SdkFadadaSigningGateway.requiredActorEmbedUrl(response))
                .isEqualTo("https://80005620.uat-e.fadada.com/connect?ticket=one-time");
    }

    @Test
    void doesNotFallBackToShortUrlWhenEmbedUrlIsMissing() {
        SignTaskActorGetUrlRes response = new SignTaskActorGetUrlRes();
        response.setActorSignTaskUrl("https://test.fdd9.cn/short-link");

        assertThatThrownBy(() -> SdkFadadaSigningGateway.requiredActorEmbedUrl(response))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未获取到小程序合同签署地址，请稍后重试");
    }

    @Test
    void selectsTheLastSignedContractPageFromProviderImageArchive() throws Exception {
        byte[] firstPage = png((byte) 1);
        byte[] lastPage = png((byte) 9);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            add(zip, "contract/page-1.png", firstPage);
            add(zip, "contract/page-10.png", lastPage);
            add(zip, "contract/readme.txt", "ignored".getBytes());
        }

        assertThat(SdkFadadaSigningGateway.lastPngFromZip(output.toByteArray()))
                .containsExactly(lastPage);
    }

    private byte[] png(byte marker) {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, marker};
    }

    private void add(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
