package com.tradepass.module.file;

import com.tradepass.framework.runtime.core.ServiceLauncher;
import com.tradepass.module.file.framework.config.FileRuntimeConfiguration;

/**
 * 文件存储服务启动类。
 */
public final class FileServerApplication {
    public static void main(String[] args) {
        ServiceLauncher.run("file", 1115, FileRuntimeConfiguration.class, args);
    }
}
