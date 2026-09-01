package com.aengine.persistence.db;

import com.aengine.persistence.AbstractEntity;
import com.aengine.persistence.annotation.CRepository;
import com.aengine.persistence.annotation.Column;
import com.aengine.persistence.annotation.Pk;
import com.aengine.persistence.annotation.Table;
import com.aengine.persistence.dialect.PostgresDialect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRepositoryDialectTest {
    @AfterEach void clearManager() { DBManager.getInstance().clear(); }
    @Test void postgresCrudUsesDoubleQuotesAndValuesKeyword() {
        List<String> sql = new ArrayList<>();
        Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        sql.add((String) args[0]);
                        return statement();
                    }
                    return DBDialectTransactionTest.defaultValue(method.getReturnType());
                });
        DBManager.getInstance().add(new DB("pg_test", DBDialectTransactionTest.dataSource(connection),
                0, 100, new PostgresDialect()));
        TestRepository repository = new TestRepository();
        TestEntity entity = new TestEntity(); entity.id = 7; entity.name = "echo";
        repository.add(entity); repository.save(entity); repository.remove(entity);
        assertThat(sql).containsExactly(
                "INSERT INTO \"t_dialect_entity\" (\"id\", \"name\") VALUES (?, ?)",
                "UPDATE \"t_dialect_entity\" SET \"name\" = ? WHERE \"id\" = ?",
                "DELETE FROM \"t_dialect_entity\" WHERE \"id\" = ?");
    }

    @Test void validateModeAcceptsExistingTableAndColumnsWithoutDdl() {
        RecordingDB db = new RecordingDB(SchemaMode.VALIDATE, true, true, true);
        DBManager.getInstance().add(db);
        new ValidatedRepository();
        assertThat(db.updates).isEmpty();
        assertThat(db.queries).hasSize(3);
    }

    @Test void validateModeFailsFastWhenAColumnIsMissing() {
        RecordingDB db = new RecordingDB(SchemaMode.VALIDATE, true, true, false);
        DBManager.getInstance().add(db);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(ValidatedRepository::new))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema validate failed")
                .hasRootCauseMessage("required columns are missing from t_validated_entity: [name]");
    }

    @Test void noneModeDoesNotTouchSchema() {
        RecordingDB db = new RecordingDB(SchemaMode.NONE);
        DBManager.getInstance().add(db);
        new ValidatedRepository();
        assertThat(db.queries).isEmpty();
        assertThat(db.updates).isEmpty();
    }
    private static PreparedStatement statement() {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) ->
                        DBDialectTransactionTest.defaultValue(method.getReturnType()));
    }
    @Table(name = "t_dialect_entity", autoCreate = false)
    static class TestEntity implements AbstractEntity {
        @Pk(auto = false) @Column(name = "id") long id;
        @Column(name = "name", length = 64) String name;
    }
    @CRepository(source = "pg_test") static class TestRepository extends JDBCRepository<TestEntity> { }

    @Table(name = "t_validated_entity")
    static class ValidatedEntity implements AbstractEntity {
        @Pk(auto = false) @Column(name = "id") long id;
        @Column(name = "name", length = 64) String name;
    }
    @CRepository(source = "schema_test")
    static class ValidatedRepository extends JDBCRepository<ValidatedEntity> { }

    static class RecordingDB extends DB {
        final List<Boolean> answers = new ArrayList<>();
        final List<String> queries = new ArrayList<>();
        final List<String> updates = new ArrayList<>();
        RecordingDB(SchemaMode mode, Boolean... answers) {
            super("schema_test", DBDialectTransactionTest.dataSource(null), 0, 100,
                    new PostgresDialect(), mode);
            this.answers.addAll(List.of(answers));
        }
        @Override public List<Map<String, Object>> query(String sql, PreparedStatementBinder binder)
                throws SQLException {
            queries.add(sql);
            boolean present = !answers.isEmpty() && answers.remove(0);
            return present ? List.of(Map.of("ok", 1)) : List.of();
        }
        @Override public int update(String sql) {
            updates.add(sql);
            return 0;
        }
    }
}
