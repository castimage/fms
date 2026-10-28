package net.castimage.fms_backend.security;

import net.castimage.fms_backend.Utils.UuidV7;
import net.castimage.fms_backend.Model.vo.LedgerView;
import net.castimage.fms_backend.common.handler.UuidTypeHandler;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MyBatis record 结果类型映射测试，注解式 Mapper 的 resultType
 * （VoucherListView/VoucherLineView/LedgerView/TrialBalanceRow）为 record，
 * 本测试以 mocked jdbc 驱动 DefaultResultSetHandler 完整结果映射路径，列别名
 * （account_id 映射 accountId，map-underscore-to-camel-case）必须经 record 规范构造器
 * 按组件名自动装配。uuid 列依赖 UuidTypeHandler 注册（与 production
 * type-handlers-package 同构）。
 */
class RecordResultTypeTest {

    @Test
    void recordResultTypeMapsColumnsViaCanonicalConstructor() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(new UuidTypeHandler());

        ResultMap resultMap = new ResultMap.Builder(configuration, "probe",
                LedgerView.class, List.of(), Boolean.TRUE).build();
        MappedStatement ms = new MappedStatement.Builder(configuration, "probe.select",
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap)).build();
        BoundSql boundSql = ms.getBoundSql(null);

        ResultSetMetaData md = mock(ResultSetMetaData.class);
        String[] cols = {"account_id", "account_code", "account_name", "direction",
                "opening_balance", "period_debit", "period_credit", "closing_balance"};
        when(md.getColumnCount()).thenReturn(cols.length);
        for (int i = 0; i < cols.length; i++) {
            when(md.getColumnLabel(i + 1)).thenReturn(cols[i]);
            when(md.getColumnName(i + 1)).thenReturn(cols[i]);
            when(md.getColumnType(i + 1)).thenReturn(i == 0 ? Types.OTHER : i >= 4 ? Types.NUMERIC : Types.VARCHAR);
            when(md.getColumnClassName(i + 1)).thenReturn("java.lang.Object");
        }

        UUID accountId = UuidV7.next();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(md);
        when(rs.next()).thenReturn(true, false);
        when(rs.isClosed()).thenReturn(false);
        when(rs.getType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);
        // UuidTypeHandler.getNullableResult 走 rs.getObject(columnName) 单参重载
        when(rs.getObject("account_id")).thenReturn(accountId);
        when(rs.getObject("accountId")).thenReturn(accountId);
        when(rs.getString("account_code")).thenReturn("1001");
        when(rs.getString("account_name")).thenReturn("现金");
        when(rs.getString("direction")).thenReturn("DEBIT");
        when(rs.getBigDecimal("opening_balance")).thenReturn(new BigDecimal("1.00"));
        when(rs.getBigDecimal("period_debit")).thenReturn(new BigDecimal("2.00"));
        when(rs.getBigDecimal("period_credit")).thenReturn(new BigDecimal("3.00"));
        when(rs.getBigDecimal("closing_balance")).thenReturn(new BigDecimal("4.00"));

        Statement stmt = mock(Statement.class);
        when(stmt.getResultSet()).thenReturn(rs);
        when(stmt.getConnection()).thenReturn(mock(Connection.class));
        when(stmt.getMoreResults()).thenReturn(false);

        DefaultResultSetHandler handler = new DefaultResultSetHandler(mock(Executor.class), ms,
                mock(ParameterHandler.class), null, boundSql, RowBounds.DEFAULT);
        List<Object> results = handler.handleResultSets(stmt);

        assertEquals(1, results.size(), "record 结果映射必须产出一行");
        LedgerView row = (LedgerView) results.getFirst();
        assertNotNull(row);
        assertEquals(accountId, row.accountId());
        assertEquals("1001", row.accountCode());
        assertEquals("现金", row.accountName());
        assertEquals("DEBIT", row.direction());
        assertEquals(new BigDecimal("1.00"), row.openingBalance());
        assertEquals(new BigDecimal("2.00"), row.periodDebit());
        assertEquals(new BigDecimal("3.00"), row.periodCredit());
        assertEquals(new BigDecimal("4.00"), row.closingBalance());
    }
}
