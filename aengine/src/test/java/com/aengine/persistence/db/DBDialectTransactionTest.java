package com.aengine.persistence.db;

import com.aengine.persistence.dialect.MySqlDialect;
import com.aengine.persistence.dialect.PostgresDialect;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DBDialectTransactionTest {
    @Test void legacyConstructorDefaultsToMySql() {
        assertThat(new DB("legacy", dataSource(null), 0).getDialect()).isInstanceOf(MySqlDialect.class);
    }
    @Test void explicitPostgresDialectIsRetained() {
        assertThat(new DB("echo", dataSource(null), 0, 100, new PostgresDialect()).getDialect())
                .isInstanceOf(PostgresDialect.class);
    }
    @Test void transactionCommitsAndRestoresAutoCommit() throws Exception {
        List<String> calls = new ArrayList<>();
        DB db = new DB("echo", dataSource(connection(calls)), 0, 100, new PostgresDialect());
        String result = db.inTransaction(conn -> "done");
        assertThat(result).isEqualTo("done");
        assertThat(calls).containsExactly("getAutoCommit", "setAutoCommit:false", "commit",
                "setAutoCommit:true", "close");
    }
    @Test void transactionRollsBackAndPreservesOriginalFailure() throws Exception {
        List<String> calls = new ArrayList<>();
        DB db = new DB("echo", dataSource(connection(calls)), 0, 100, new PostgresDialect());
        SQLException failure = new SQLException("write failed");
        assertThatThrownBy(() -> db.inTransaction(conn -> { throw failure; })).isSameAs(failure);
        assertThat(calls).containsExactly("getAutoCommit", "setAutoCommit:false", "rollback",
                "setAutoCommit:true", "close");
    }
    private static Connection connection(List<String> calls) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    calls.add(method.getName() + ("setAutoCommit".equals(method.getName()) ? ":" + args[0] : ""));
                    if ("getAutoCommit".equals(method.getName())) return true;
                    return defaultValue(method.getReturnType());
                });
    }
    static DataSource dataSource(Connection connection) {
        return new DataSource() {
            public Connection getConnection() { return connection; }
            public Connection getConnection(String u, String p) { return connection; }
            public PrintWriter getLogWriter() { return null; }
            public void setLogWriter(PrintWriter out) { }
            public void setLoginTimeout(int seconds) { }
            public int getLoginTimeout() { return 0; }
            public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
            public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("unwrap"); }
            public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
