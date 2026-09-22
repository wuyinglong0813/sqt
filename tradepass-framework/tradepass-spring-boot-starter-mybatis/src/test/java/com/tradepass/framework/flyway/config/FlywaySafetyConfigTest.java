package com.tradepass.framework.flyway.config;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlywaySafetyConfigTest {
    @Test
    void allowsMigrationWhenDroppedTablesDoNotExist() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet tables = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("tradepass");
        when(metadata.getTables(anyString(), any(), anyString(), any())).thenReturn(tables);
        when(tables.next()).thenReturn(false);

        assertThatCode(() -> FlywaySafetyConfig.assertDroppedTablesEmpty(dataSource)).doesNotThrowAnyException();
    }

    @Test
    void blocksMigrationWhenAProtectedTableContainsData() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet tables = mock(ResultSet.class);
        Statement statement = mock(Statement.class);
        ResultSet count = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("tradepass");
        when(metadata.getTables(anyString(), any(), anyString(), any())).thenReturn(tables);
        when(tables.next()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(count);
        when(count.next()).thenReturn(true);
        when(count.getLong(1)).thenReturn(1L);

        assertThatThrownBy(() -> FlywaySafetyConfig.assertDroppedTablesEmpty(dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("破坏性 V5 迁移")
                .hasMessageContaining("请先备份");
    }
}
