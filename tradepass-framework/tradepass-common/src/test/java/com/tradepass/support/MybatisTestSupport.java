package com.tradepass.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

public final class MybatisTestSupport {
    private MybatisTestSupport() {
    }

    public static void initialize(Class<?>... entityTypes) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entityType : entityTypes) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, entityType.getName());
            assistant.setCurrentNamespace(entityType.getName());
            TableInfoHelper.initTableInfo(assistant, entityType);
        }
    }
}
