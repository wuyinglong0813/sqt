package com.tradepass.module.contract.controller.app.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopSignFramePageTest {
    @Test
    void scalesANarrowDesktopWindowWithoutOpeningAnArbitraryAddress() {
        String html = DesktopSignFramePage.html();

        assertThat(html).contains("var DESIGN = 1280;");
        assertThat(html).contains("frame.style.transform = 'scale(' + scale + ')'");
        assertThat(html).contains("host === 'fadada.com' || host.endsWith('.fadada.com')");
        assertThat(html).contains("签署页面地址无效");
        assertThat(html).doesNotContain("javascript:");
    }
}
