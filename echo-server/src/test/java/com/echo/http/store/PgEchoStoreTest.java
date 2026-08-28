package com.echo.http.store;

import com.aengine.util.id.IDGenerator;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.model.Models.FlowerLog;
import com.echo.http.model.Models.LifeBookItem;
import com.echo.http.model.Models.MessageEntry;
import com.echo.http.model.Models.PetEcho;
import com.echo.http.model.Models.PetProfile;
import com.echo.http.model.Models.Postcard;
import com.echo.http.model.Models.RecordEntry;
import com.echo.http.model.Models.RelationEntry;
import com.echo.infra.persistence.PgDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link PgEchoStore} 落库集成测试（真实 PostgreSQL）。
 *
 * <p><b>门控</b>：仅当设置环境变量 {@code ECHO_TEST_PG_URL}（如
 * {@code jdbc:postgresql://127.0.0.1:55432/echo}）时运行；否则 {@code assumeTrue} 跳过，
 * 保证无库 CI / 常规 {@code mvn test} 全绿。可选 {@code ECHO_TEST_PG_USER}/{@code ECHO_TEST_PG_PASSWORD}。</p>
 *
 * <p>覆盖：各表 CRUD、lifeBook/traits JSON 往返、回写（reply/read/relation）、每日献花额度、
 * 记得状态开关、明信片覆盖写与解锁；并用<b>第二个 store 实例</b>读取同库，验证"重启后数据仍在"。</p>
 */
class PgEchoStoreTest {

    private static PgDb db;
    private final IDGenerator ids = new IDGenerator(7L);

    @BeforeAll
    static void connect() {
        String url = System.getenv("ECHO_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(),
                "设置 ECHO_TEST_PG_URL 以运行 PgEchoStore 集成测试（如 jdbc:postgresql://127.0.0.1:55432/echo）");
        Properties p = new Properties();
        p.setProperty("db.name", "echo");
        p.setProperty("jdbcUrl", url);
        p.setProperty("driverClassName", "org.postgresql.Driver");
        p.setProperty("username", System.getenv().getOrDefault("ECHO_TEST_PG_USER", "echo"));
        String pwd = System.getenv("ECHO_TEST_PG_PASSWORD");
        if (pwd != null) {
            p.setProperty("password", pwd);
        }
        p.setProperty("maximumPoolSize", "4");
        db = new PgDb(p);
        // 触发一次真实连接；连不上则跳过（惰性连接下构造不报错）
        try {
            db.query("SELECT 1", null);
        } catch (SQLException e) {
            assumeTrue(false, "无法连接测试库，跳过：" + e.getMessage());
        }
    }

    @BeforeEach
    void cleanup() throws SQLException {
        assumeTrue(db != null);
        // 先建表（幂等），再清空，保证确定性
        new PgEchoStore(db, ids);
        for (String t : new String[]{"t_account_profile", "t_pet", "t_pet_echo", "t_flower_log",
                "t_remember", "t_postcard", "t_record", "t_message", "t_relation"}) {
            db.update("TRUNCATE TABLE \"" + t + "\"");
        }
    }

    private PgEchoStore store() {
        return new PgEchoStore(db, ids);
    }

    @Test
    void accountProfileRoundTripAndDeviceLookup() {
        PgEchoStore s = store();
        AccountProfile p = new AccountProfile();
        p.accountId = ids.nextId();
        p.deviceId = "dev-" + p.accountId;
        p.guest = true;
        p.nickname = "旅人";
        p.visibilityDefault = "private";
        p.hasPet = false;
        p.trainConsent = true;
        p.createTime = System.currentTimeMillis();
        s.putProfile(p);

        // 新实例读取（模拟重启）
        PgEchoStore s2 = store();
        AccountProfile got = s2.profile(p.accountId);
        assertThat(got).isNotNull();
        assertThat(got.deviceId).isEqualTo(p.deviceId);
        assertThat(got.trainConsent).isTrue();
        assertThat(got.visibilityDefault).isEqualTo("private");
        assertThat(s2.accountByDevice(p.deviceId)).isEqualTo(p.accountId);
        assertThat(s2.accountByDevice("nope")).isNull();

        // UPSERT：hasPet 翻转
        p.hasPet = true;
        s.putProfile(p);
        assertThat(store().profile(p.accountId).hasPet).isTrue();
    }

    @Test
    void petRoundTripWithLifeBookAndTraitsJson() {
        PgEchoStore s = store();
        PetProfile pet = new PetProfile();
        pet.petId = String.valueOf(ids.nextId());
        pet.ownerAccountId = ids.nextId();
        pet.name = "麦麦";
        pet.species = "金毛";
        pet.temperature = 72.0;
        pet.visibility = "public";
        pet.trainConsent = true;
        pet.createTime = System.currentTimeMillis();
        pet.lifeBook.add(new LifeBookItem("相遇那天", 2019, "在雨里遇见你"));
        pet.lifeBook.add(new LifeBookItem("最后一程", 2024, "谢谢你来过"));
        pet.traits.add("温柔");
        pet.traits.add("粘人");
        s.putPet(pet);

        PetProfile got = store().petById(pet.petId);
        assertThat(got).isNotNull();
        assertThat(got.name).isEqualTo("麦麦");
        assertThat(got.visibility).isEqualTo("public");
        assertThat(got.lifeBook).hasSize(2);
        assertThat(got.lifeBook.get(0).title).isEqualTo("相遇那天");
        assertThat(got.lifeBook.get(0).year).isEqualTo(2019);
        assertThat(got.traits).containsExactly("温柔", "粘人");

        assertThat(store().petOfOwner(pet.ownerAccountId).petId).isEqualTo(pet.petId);
        assertThat(store().allPets()).extracting(x -> x.petId).contains(pet.petId);

        // 回访：温度/收花计数更新回写
        got.temperature = 80.0;
        got.flowersReceived += 3;
        store().putPet(got);
        PetProfile after = store().petById(pet.petId);
        assertThat(after.temperature).isEqualTo(80.0);
        assertThat(after.flowersReceived).isEqualTo(3);
    }

    @Test
    void echoUpsertAndReplyWriteBack() {
        PgEchoStore s = store();
        String petId = String.valueOf(ids.nextId());
        PetEcho e = new PetEcho();
        e.echoId = String.valueOf(ids.nextId());
        e.petId = petId;
        e.text = "今天这里的风很轻";
        e.tone = "gentle";
        e.createdAt = System.currentTimeMillis();
        s.addEcho(e);

        // 回复回写（同 echoId → UPSERT，不重复）
        e.reply = "我也想你了";
        s.addEcho(e);

        List<PetEcho> list = store().echoesOfPet(petId);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).reply).isEqualTo("我也想你了");
        assertThat(store().echoById(e.echoId).text).isEqualTo("今天这里的风很轻");
    }

    @Test
    void flowerDailyQuotaAndBondSum() {
        PgEchoStore s = store();
        long from = ids.nextId();
        long owner = ids.nextId();
        int day = 20260727;
        s.addFlowerLog(flower(from, owner, 2, "daily", day));
        s.addFlowerLog(flower(from, owner, 1, "daily", day));
        s.addFlowerLog(flower(from, owner, 5, "gift", day)); // 非 daily 不计额度

        assertThat(store().flowersUsedToday(from, day)).isEqualTo(3);
        assertThat(store().flowersUsedToday(from, day + 1)).isZero();
        assertThat(store().flowersFromTo(from, owner)).isEqualTo(8); // 含 gift 的总羁绊
    }

    private FlowerLog flower(long from, long owner, int count, String type, int day) {
        FlowerLog l = new FlowerLog();
        l.id = String.valueOf(ids.nextId());
        l.windowId = String.valueOf(ids.nextId());
        l.fromAccountId = from;
        l.toOwnerAccountId = owner;
        l.count = count;
        l.type = type;
        l.day = day;
        l.createdAt = System.currentTimeMillis();
        return l;
    }

    @Test
    void rememberIsStateSwitchNotCounter() {
        PgEchoStore s = store();
        String petId = String.valueOf(ids.nextId());
        long a1 = ids.nextId();
        long a2 = ids.nextId();
        assertThat(s.setRemember(petId, a1, true)).isTrue();
        assertThat(s.setRemember(petId, a1, true)).isFalse(); // 幂等：再次记得无变化
        assertThat(s.setRemember(petId, a2, true)).isTrue();
        assertThat(store().isRemembered(petId, a1)).isTrue();
        assertThat(store().rememberFaces(petId)).containsExactlyInAnyOrder(a1, a2);
        assertThat(s.setRemember(petId, a1, false)).isTrue(); // 取消
        assertThat(store().rememberFaces(petId)).containsExactly(a2);
    }

    @Test
    void postcardReplaceAndUnlock() {
        PgEchoStore s = store();
        String petId = String.valueOf(ids.nextId());
        Postcard c1 = postcard(petId, "初见");
        Postcard c2 = postcard(petId, "同框");
        s.putPostcards(petId, List.of(c1, c2));
        assertThat(store().postcards(petId)).hasSize(2);

        // 解锁：locked 翻转后覆盖写
        List<Postcard> cards = store().postcards(petId);
        cards.get(0).locked = false;
        s.putPostcards(petId, cards);
        assertThat(store().postcard(petId, c1.id).locked).isFalse();
        assertThat(store().postcard(petId, c2.id).locked).isTrue();
    }

    private Postcard postcard(String petId, String caption) {
        Postcard c = new Postcard();
        c.id = String.valueOf(ids.nextId());
        c.petId = petId;
        c.caption = caption;
        c.locked = true;
        c.createdAt = System.currentTimeMillis();
        return c;
    }

    @Test
    void recordsMessagesRelationsWriteBack() {
        PgEchoStore s = store();
        long acc = ids.nextId();

        RecordEntry r = new RecordEntry();
        r.id = String.valueOf(ids.nextId());
        r.accountId = acc;
        r.scope = "self";
        r.text = "今天很想你";
        r.createdAt = System.currentTimeMillis();
        s.addRecord(r);
        assertThat(store().records(acc)).hasSize(1);

        MessageEntry m = new MessageEntry();
        m.id = String.valueOf(ids.nextId());
        m.accountId = acc;
        m.kind = "system";
        m.title = "欢迎";
        m.read = false;
        m.createdAt = System.currentTimeMillis();
        s.addMessage(m);
        m.read = true;
        s.updateMessage(m); // 回写已读
        assertThat(store().messages(acc).get(0).read).isTrue();

        RelationEntry rel = new RelationEntry();
        rel.id = String.valueOf(ids.nextId());
        rel.accountId = acc;
        rel.peerAccountId = ids.nextId();
        rel.peerName = "阿橘";
        rel.createdAt = System.currentTimeMillis();
        s.addRelation(rel);
        rel.priority = true;
        rel.hasUnseenReel = false;
        rel.mutedUntil = 123456789L;
        s.updateRelation(rel); // 回写优先级/静音
        RelationEntry got = store().relation(acc, rel.id);
        assertThat(got.priority).isTrue();
        assertThat(got.mutedUntil).isEqualTo(123456789L);
        assertThat(got.peerName).isEqualTo("阿橘");
    }
}
