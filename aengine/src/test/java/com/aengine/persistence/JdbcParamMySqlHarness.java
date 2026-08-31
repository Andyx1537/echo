package com.aengine.persistence;

import com.aengine.persistence.annotation.CRepository;
import com.aengine.persistence.annotation.Column;
import com.aengine.persistence.annotation.Index;
import com.aengine.persistence.annotation.Pk;
import com.aengine.persistence.annotation.Table;
import com.aengine.persistence.db.DB;
import com.aengine.persistence.db.DBManager;
import com.aengine.persistence.db.JDBCRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 针对真实本地 MySQL 的 JDBC 参数化集成测试 harness（不进 CI / mvn test）。
 *
 * <p>验证目标：</p>
 * <ul>
 *   <li>CRUD：add / get(id) / save(update) / remove 全链路正确（参数化 PreparedStatement）。</li>
 *   <li>条件查询：list(field,value) / get(field,value) 走 ? 占位绑定且结果正确。</li>
 *   <li>SQL 注入防护：把注入串作为字段值，应被当作字面量，表不被破坏、其它数据不受影响。</li>
 * </ul>
 *
 * <p>运行（Aengine 目录，需本地 MySQL 且 aengine/aengine@aengine_test 就绪）：</p>
 * <pre>
 * mvn -q test-compile \
 *   org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
 *   -Dexec.mainClass=com.aengine.persistence.JdbcParamMySqlHarness \
 *   -Dexec.classpathScope=test
 * </pre>
 */
public class JdbcParamMySqlHarness {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/aengine_test?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        cfg.setUsername("aengine");
        cfg.setPassword("aengine");
        cfg.setMaximumPoolSize(4);
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        HikariDataSource ds = new HikariDataSource(cfg);

        DB db = new DB("it_db", ds, 0);
        DBManager.getInstance().add(db);

        // 干净起步：若残留旧表先删
        db.update("DROP TABLE IF EXISTS t_player_it");

        PlayerRepo repo = new PlayerRepo(); // 构造时自动建表

        // 1) add -> 自增主键回填
        PlayerEntity p1 = new PlayerEntity();
        p1.name = "Alice";
        p1.level = 9;
        p1.score = 12.5;
        p1.vip = true;
        repo.add(p1);
        check("add 回填自增主键", p1.id > 0);

        PlayerEntity p2 = new PlayerEntity();
        p2.name = "Bob";
        p2.level = 3;
        p2.score = 4.0;
        p2.vip = false;
        repo.add(p2);
        check("add 第二条", p2.id > 0 && p2.id != p1.id);

        // 2) get(id) 参数化 SELECT WHERE pk=?
        PlayerEntity got = repo.get(p1.id);
        check("get(id) 命中", got != null && "Alice".equals(got.name) && got.level == 9
                && Math.abs(got.score - 12.5) < 1e-6 && got.vip);

        // 3) save -> UPDATE
        got.level = 42;
        got.score = 99.9;
        repo.save(got);
        PlayerEntity afterUpdate = repo.get(p1.id);
        check("save 更新生效", afterUpdate.level == 42 && Math.abs(afterUpdate.score - 99.9) < 1e-6);

        // 4) list(field,value) 参数化 WHERE
        List<PlayerEntity> byName = repo.list("name", "Bob");
        check("list(name=Bob) 命中唯一", byName != null && byName.size() == 1 && byName.get(0).id == p2.id);

        PlayerEntity byNameOne = repo.get("name", "Alice");
        check("get(name=Alice) 命中", byNameOne != null && byNameOne.id == p1.id);

        // 5) SQL 注入防护：注入串作为字段值，必须被当作字面量
        String evil = "Robert'); DROP TABLE t_player_it;--";
        PlayerEntity hacker = new PlayerEntity();
        hacker.name = evil;
        hacker.level = 1;
        hacker.score = 0.0;
        hacker.vip = false;
        repo.add(hacker); // INSERT 也是参数化
        check("注入串作为名称可正常插入(字面量)", hacker.id > 0);

        // 用注入串做条件查询：应精确匹配该行，且不会执行 DROP
        List<PlayerEntity> evilQuery = repo.list("name", evil);
        check("注入串条件查询按字面量精确匹配", evilQuery != null && evilQuery.size() == 1
                && evilQuery.get(0).id == hacker.id);

        // 关键：表必须仍然存在、且原有数据完好（若注入生效，表早被 DROP）
        List<Map<String, Object>> tables = db.query("SHOW TABLES LIKE 't_player_it'");
        check("注入未生效：表仍存在", tables != null && tables.size() == 1);
        check("注入未生效：原有数据完好", repo.get(p1.id) != null && repo.get(p2.id) != null);

        // 经典布尔注入：' OR '1'='1 作为值，不应返回全部
        List<PlayerEntity> orInjection = repo.list("name", "x' OR '1'='1");
        check("布尔注入不返回全部(应为0行)", orInjection != null && orInjection.isEmpty());

        // 6) remove
        repo.remove(p2);
        check("remove 后查不到", repo.get(p2.id) == null);
        check("remove 不误删他行", repo.get(p1.id) != null);

        // 清理
        db.update("DROP TABLE IF EXISTS t_player_it");
        ds.close();

        System.out.println("\n=== 结果: PASS=" + passed + ", FAIL=" + failed + " ===");
        if (failed > 0) {
            System.out.println(">>> 存在失败用例");
            System.exit(1);
        }
        System.out.println(">>> 全部通过：参数化查询正确且注入防护有效");
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }

    @Table(name = "t_player_it", comment = "参数化集成测试玩家表",
            index = {@Index(name = "idx_name", columns = {"name"})})
    public static class PlayerEntity implements AbstractEntity {
        @Pk
        @Column(name = "id")
        long id;

        @Column(name = "name", length = 64, notNull = false)
        String name;

        @Column(name = "level")
        int level;

        @Column(name = "score")
        double score;

        @Column(name = "vip")
        boolean vip;
    }

    @CRepository(source = "it_db")
    public static class PlayerRepo extends JDBCRepository<PlayerEntity> {
    }
}
