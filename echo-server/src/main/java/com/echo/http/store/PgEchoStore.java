package com.echo.http.store;

import com.aengine.util.id.IDGenerator;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.model.Models.FlowerLog;
import com.echo.http.model.Models.LifeBookItem;
import com.echo.http.model.Models.MessageEntry;
import com.echo.http.model.Models.Onboarding;
import com.echo.http.model.Models.PetEcho;
import com.echo.http.model.Models.PetProfile;
import com.echo.http.model.Models.Postcard;
import com.echo.http.model.Models.ReactionMark;
import com.echo.http.model.Models.RecordEntry;
import com.echo.http.model.Models.RelationEntry;
import com.echo.http.model.Models.ShadowArea;
import com.echo.http.model.Models.SpectrumNode;
import com.echo.http.model.Models.SubjectFields;
import com.echo.infra.persistence.PgDb;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link EchoStore} 的 PostgreSQL 落库实现（{@code echo.db.enabled=true} 时装配）。
 *
 * <p>裸 JDBC（经 {@link PgDb} 的 update/query）持久化耐久域：账号概要 / 往宠档案 / 近况来信 /
 * 献花流水 / 记得 / 明信片 / 记录 / 消息 / 亲友。表结构与 {@code schema.sql} 新域段一致，构造时
 * 幂等建表（{@code CREATE TABLE IF NOT EXISTS}）。</p>
 *
 * <p><b>进程内维护（无独立表）</b>：会话 token、建档流程态 onboarding、光谱 nodes/shadows —— 均为
 * 短生命周期/会话/尚未建表的数据，重启可重建，故沿用内存 Map（与 {@link InMemoryEchoStore} 一致行为）。
 * 其中账号-设备映射走 t_account_profile.deviceId 查询，保证重启后同设备游客仍能取回账号。</p>
 *
 * <p>并发：{@link #flowerLock()} 提供进程内互斥（单实例 MVP 足够；多实例的跨进程超发控制为 TODO，
 * 需借助唯一约束/事务）。</p>
 */
@Slf4j
public class PgEchoStore implements EchoStore {

    private static final Gson GSON = new Gson();

    private final PgDb db;
    private final IDGenerator idGenerator;

    // —— 进程内维护（无对应表）——
    private final Map<String, Long> tokenToAccount = new ConcurrentHashMap<>();
    private final Map<Long, List<SpectrumNode>> spectrumNodes = new ConcurrentHashMap<>();
    private final Map<Long, List<ShadowArea>> spectrumShadows = new ConcurrentHashMap<>();
    private final Map<String, Onboarding> onboardings = new ConcurrentHashMap<>();

    public PgEchoStore(PgDb db, IDGenerator idGenerator) {
        this.db = db;
        this.idGenerator = idGenerator;
        ensureSchema();
    }

    // ============================================================ 建表（幂等）
    private void ensureSchema() {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS \"t_account_profile\" ("
                + "\"accountId\" bigint NOT NULL,"
                + "\"deviceId\" varchar(128) NOT NULL DEFAULT '',"
                + "\"guest\" smallint NOT NULL DEFAULT 1,"
                + "\"nickname\" varchar(64) NOT NULL DEFAULT '',"
                + "\"avatar\" varchar(256) NOT NULL DEFAULT '',"
                + "\"visibilityDefault\" varchar(16) NOT NULL DEFAULT 'private',"
                + "\"hasPet\" smallint NOT NULL DEFAULT 0,"
                + "\"trainConsent\" smallint NOT NULL DEFAULT 0,"
                + "\"createTime\" bigint NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (\"accountId\"))",
            "CREATE UNIQUE INDEX IF NOT EXISTS \"t_account_profile_uk_device\" ON \"t_account_profile\" (\"deviceId\")",
            "CREATE TABLE IF NOT EXISTS \"t_pet\" ("
                + "\"petId\" bigint NOT NULL,"
                + "\"ownerAccountId\" bigint NOT NULL DEFAULT 0,"
                + "\"name\" varchar(64) NOT NULL DEFAULT '',"
                + "\"species\" varchar(64) NOT NULL DEFAULT '',"
                + "\"signature\" varchar(256) NOT NULL DEFAULT '',"
                + "\"temperature\" double precision NOT NULL DEFAULT 72,"
                + "\"visibility\" varchar(16) NOT NULL DEFAULT 'private',"
                + "\"coverGradient\" varchar(64) NOT NULL DEFAULT '',"
                + "\"coverEmoji\" varchar(16) NOT NULL DEFAULT '',"
                + "\"memoryCaption\" text,"
                + "\"lifeBook\" text,"
                + "\"lastVisitAt\" bigint NOT NULL DEFAULT 0,"
                + "\"seenCount\" bigint NOT NULL DEFAULT 0,"
                + "\"flowersReceived\" bigint NOT NULL DEFAULT 0,"
                + "\"trainConsent\" smallint NOT NULL DEFAULT 0,"
                // 主体类型四字段。🔴 兜底 'other'/'default' 不是随手选的，见 Models.SubjectFields；
                //    machine/userSubjectType 可空（null = 机器没判 / 用户没答，是有意义的状态）。
                + "\"subjectType\" varchar(16) NOT NULL DEFAULT 'other',"
                + "\"machineSubjectType\" varchar(16),"
                + "\"userSubjectType\" varchar(16),"
                + "\"subjectSource\" varchar(16) NOT NULL DEFAULT 'default',"
                + "\"traits\" text,"
                + "\"createTime\" bigint NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (\"petId\"))",
            "CREATE INDEX IF NOT EXISTS \"t_pet_idx_owner\" ON \"t_pet\" (\"ownerAccountId\")",
            "CREATE INDEX IF NOT EXISTS \"t_pet_idx_visibility\" ON \"t_pet\" (\"visibility\")",
            // 已有宠物表补列（幂等）。🔴 存量行落 'other'/'default'，与 SR-D2「只管新上传、
            //    存量不回溯」同向：存量行没有被判定过，兜底值正是「没判过」该有的值。
            "ALTER TABLE \"t_pet\" ADD COLUMN IF NOT EXISTS "
                + "\"subjectType\" varchar(16) NOT NULL DEFAULT 'other'",
            "ALTER TABLE \"t_pet\" ADD COLUMN IF NOT EXISTS \"machineSubjectType\" varchar(16)",
            "ALTER TABLE \"t_pet\" ADD COLUMN IF NOT EXISTS \"userSubjectType\" varchar(16)",
            "ALTER TABLE \"t_pet\" ADD COLUMN IF NOT EXISTS "
                + "\"subjectSource\" varchar(16) NOT NULL DEFAULT 'default'",
            "CREATE TABLE IF NOT EXISTS \"t_pet_echo\" ("
                + "\"echoId\" bigint NOT NULL,"
                + "\"petId\" bigint NOT NULL DEFAULT 0,"
                + "\"text\" text,"
                + "\"tone\" varchar(32) NOT NULL DEFAULT 'gentle',"
                + "\"reply\" text,"
                + "\"createdAt\" bigint NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (\"echoId\"))",
            "CREATE INDEX IF NOT EXISTS \"t_pet_echo_idx_pet\" ON \"t_pet_echo\" (\"petId\")",
            "CREATE TABLE IF NOT EXISTS \"t_flower_log\" ("
                + "\"id\" bigint NOT NULL,"
                + "\"windowId\" bigint NOT NULL DEFAULT 0,"
                + "\"fromAccountId\" bigint NOT NULL DEFAULT 0,"
                + "\"toOwnerAccountId\" bigint NOT NULL DEFAULT 0,"
                + "\"count\" integer NOT NULL DEFAULT 0,"
                + "\"type\" varchar(16) NOT NULL DEFAULT 'daily',"
                + "\"message\" text,"
                + "\"anonymous\" smallint NOT NULL DEFAULT 0,"
                + "\"day\" integer NOT NULL DEFAULT 0,"
                + "\"createdAt\" bigint NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (\"id\"))",
            "CREATE INDEX IF NOT EXISTS \"t_flower_log_idx_from_day\" ON \"t_flower_log\" (\"fromAccountId\", \"day\")",
            "CREATE INDEX IF NOT EXISTS \"t_flower_log_idx_owner\" ON \"t_flower_log\" (\"toOwnerAccountId\")",
            "CREATE TABLE IF NOT EXISTS \"t_remember\" ("
                + "\"id\" bigint NOT NULL,"
                + "\"petId\" bigint NOT NULL DEFAULT 0,"
                + "\"accountId\" bigint NOT NULL DEFAULT 0,"
                + "\"createdAt\" bigint NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (\"id\"))",
            "CREATE UNIQUE INDEX IF NOT EXISTS \"t_remember_uk_pet_account\" ON \"t_remember\" (\"petId\", \"accountId\")",
            "CREATE TABLE IF NOT EXISTS \"t_reaction_seen\" ("
                + "\"ownerAccountId\" bigint NOT NULL,"
                + "\"windowId\" bigint NOT NULL,"
                + "\"seenAt\" bigint NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (\"ownerAccountId\", \"windowId\"))",
            "CREATE TABLE IF NOT EXISTS \"t_postcard\" ("
                + "\"id\" bigint NOT NULL,"
                + "\"petId\" bigint NOT NULL DEFAULT 0,"
                + "\"date\" varchar(32) NOT NULL DEFAULT '',"
                + "\"caption\" text,"
                + "\"locked\" smallint NOT NULL DEFAULT 1,"
                + "\"unlockHint\" varchar(128) NOT NULL DEFAULT '',"
                + "\"skin\" varchar(64) NOT NULL DEFAULT 'classic',"
                + "\"createdAt\" bigint NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (\"id\"))",
            "CREATE INDEX IF NOT EXISTS \"t_postcard_idx_pet\" ON \"t_postcard\" (\"petId\")",
            "CREATE TABLE IF NOT EXISTS \"t_record\" ("
                + "\"id\" bigint NOT NULL,"
                + "\"accountId\" bigint NOT NULL DEFAULT 0,"
                + "\"scope\" varchar(16) NOT NULL DEFAULT 'self',"
                + "\"text\" text,"
                + "\"createdAt\" bigint NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (\"id\"))",
            "CREATE INDEX IF NOT EXISTS \"t_record_idx_account\" ON \"t_record\" (\"accountId\")",
            "CREATE TABLE IF NOT EXISTS \"t_message\" ("
                + "\"id\" bigint NOT NULL,"
                + "\"accountId\" bigint NOT NULL DEFAULT 0,"
                + "\"kind\" varchar(16) NOT NULL DEFAULT 'system',"
                + "\"title\" varchar(128) NOT NULL DEFAULT '',"
                + "\"preview\" text,"
                + "\"read\" smallint NOT NULL DEFAULT 0,"
                + "\"routeType\" varchar(16) NOT NULL DEFAULT '',"
                + "\"routeId\" varchar(64) NOT NULL DEFAULT '',"
                + "\"createdAt\" bigint NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (\"id\"))",
            "CREATE INDEX IF NOT EXISTS \"t_message_idx_account\" ON \"t_message\" (\"accountId\")",
            "CREATE TABLE IF NOT EXISTS \"t_relation\" ("
                + "\"id\" bigint NOT NULL,"
                + "\"accountId\" bigint NOT NULL DEFAULT 0,"
                + "\"peerAccountId\" bigint NOT NULL DEFAULT 0,"
                + "\"peerName\" varchar(64) NOT NULL DEFAULT '',"
                + "\"peerAvatar\" varchar(256) NOT NULL DEFAULT '',"
                + "\"online\" smallint NOT NULL DEFAULT 0,"
                + "\"priority\" smallint NOT NULL DEFAULT 0,"
                + "\"mutedUntil\" bigint NOT NULL DEFAULT 0,"
                + "\"hasUnseenReel\" smallint NOT NULL DEFAULT 0,"
                + "\"lastActiveAt\" bigint NOT NULL DEFAULT 0,"
                + "\"createdAt\" bigint NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (\"id\"))",
            "ALTER TABLE \"t_relation\" ADD COLUMN IF NOT EXISTS \"lastActiveAt\" bigint NOT NULL DEFAULT 0",
            "CREATE UNIQUE INDEX IF NOT EXISTS \"t_relation_uk_account_peer\" ON \"t_relation\" (\"accountId\", \"peerAccountId\")",
            "CREATE INDEX IF NOT EXISTS \"t_relation_idx_account\" ON \"t_relation\" (\"accountId\")",
        };
        try {
            for (String sql : ddl) {
                db.update(sql);
            }
            log.info("PgEchoStore 建表完成（HTTP 新域 9 表 + 索引，幂等）");
        } catch (SQLException e) {
            throw new IllegalStateException("PgEchoStore 建表失败", e);
        }
    }

    // ============================================================ token/account
    @Override
    public void bindToken(String token, long accountId) {
        tokenToAccount.put(token, accountId);
    }

    @Override
    public Long resolveToken(String token) {
        return token == null ? null : tokenToAccount.get(token);
    }

    @Override
    public Long accountByDevice(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        List<Map<String, Object>> rows = query(
                "SELECT \"accountId\" FROM \"t_account_profile\" WHERE \"deviceId\" = ?",
                ps -> ps.setString(1, deviceId));
        return rows.isEmpty() ? null : asLong(rows.get(0).get("accountId"));
    }

    @Override
    public void putProfile(AccountProfile p) {
        update("INSERT INTO \"t_account_profile\" "
                + "(\"accountId\",\"deviceId\",\"guest\",\"nickname\",\"avatar\",\"visibilityDefault\",\"hasPet\",\"trainConsent\",\"createTime\") "
                + "VALUES (?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (\"accountId\") DO UPDATE SET "
                + "\"deviceId\"=EXCLUDED.\"deviceId\",\"guest\"=EXCLUDED.\"guest\",\"nickname\"=EXCLUDED.\"nickname\","
                + "\"avatar\"=EXCLUDED.\"avatar\",\"visibilityDefault\"=EXCLUDED.\"visibilityDefault\","
                + "\"hasPet\"=EXCLUDED.\"hasPet\",\"trainConsent\"=EXCLUDED.\"trainConsent\"",
                ps -> {
                    ps.setLong(1, p.accountId);
                    ps.setString(2, p.deviceId == null ? "" : p.deviceId);
                    ps.setInt(3, b2i(p.guest));
                    ps.setString(4, ns(p.nickname));
                    ps.setString(5, ns(p.avatar));
                    ps.setString(6, ns(p.visibilityDefault));
                    ps.setInt(7, b2i(p.hasPet));
                    ps.setInt(8, b2i(p.trainConsent));
                    ps.setLong(9, p.createTime);
                });
    }

    @Override
    public AccountProfile profile(long accountId) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM \"t_account_profile\" WHERE \"accountId\" = ?",
                ps -> ps.setLong(1, accountId));
        return rows.isEmpty() ? null : toProfile(rows.get(0));
    }

    private AccountProfile toProfile(Map<String, Object> r) {
        AccountProfile p = new AccountProfile();
        p.accountId = asLong(r.get("accountId"));
        p.deviceId = asStr(r.get("deviceId"));
        p.guest = asBool(r.get("guest"));
        p.nickname = asStr(r.get("nickname"));
        p.avatar = asStr(r.get("avatar"));
        p.visibilityDefault = asStr(r.get("visibilityDefault"));
        p.hasPet = asBool(r.get("hasPet"));
        p.trainConsent = asBool(r.get("trainConsent"));
        p.createTime = asLong(r.get("createTime"));
        return p;
    }

    // ============================================================ pet
    @Override
    public void putPet(PetProfile pet) {
        update("INSERT INTO \"t_pet\" "
                + "(\"petId\",\"ownerAccountId\",\"name\",\"species\",\"signature\",\"temperature\",\"visibility\","
                + "\"coverGradient\",\"coverEmoji\",\"memoryCaption\",\"lifeBook\",\"lastVisitAt\",\"seenCount\","
                + "\"flowersReceived\",\"trainConsent\",\"subjectType\",\"machineSubjectType\","
                + "\"userSubjectType\",\"subjectSource\",\"traits\",\"createTime\") "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (\"petId\") DO UPDATE SET "
                + "\"ownerAccountId\"=EXCLUDED.\"ownerAccountId\",\"name\"=EXCLUDED.\"name\",\"species\"=EXCLUDED.\"species\","
                + "\"signature\"=EXCLUDED.\"signature\",\"temperature\"=EXCLUDED.\"temperature\",\"visibility\"=EXCLUDED.\"visibility\","
                + "\"coverGradient\"=EXCLUDED.\"coverGradient\",\"coverEmoji\"=EXCLUDED.\"coverEmoji\","
                + "\"memoryCaption\"=EXCLUDED.\"memoryCaption\",\"lifeBook\"=EXCLUDED.\"lifeBook\","
                + "\"lastVisitAt\"=EXCLUDED.\"lastVisitAt\",\"seenCount\"=EXCLUDED.\"seenCount\","
                + "\"flowersReceived\"=EXCLUDED.\"flowersReceived\",\"trainConsent\"=EXCLUDED.\"trainConsent\","
                + "\"subjectType\"=EXCLUDED.\"subjectType\","
                + "\"machineSubjectType\"=EXCLUDED.\"machineSubjectType\","
                + "\"userSubjectType\"=EXCLUDED.\"userSubjectType\","
                + "\"subjectSource\"=EXCLUDED.\"subjectSource\","
                + "\"traits\"=EXCLUDED.\"traits\"",
                ps -> {
                    ps.setLong(1, lid(pet.petId));
                    ps.setLong(2, pet.ownerAccountId);
                    ps.setString(3, ns(pet.name));
                    ps.setString(4, ns(pet.species));
                    ps.setString(5, ns(pet.signature));
                    ps.setDouble(6, pet.temperature);
                    ps.setString(7, ns(pet.visibility));
                    ps.setString(8, ns(pet.coverGradient));
                    ps.setString(9, ns(pet.coverEmoji));
                    ps.setString(10, ns(pet.memoryCaption));
                    ps.setString(11, GSON.toJson(pet.lifeBook));
                    ps.setLong(12, pet.lastVisitAt);
                    ps.setLong(13, pet.seenCount);
                    ps.setLong(14, pet.flowersReceived);
                    ps.setInt(15, b2i(pet.trainConsent));
                    // 🔴 生效值与来源过归一化再写：兜底方向在 Models.SubjectFields 里，
                    //    这里不重复判断，但也不能把 null 直接写进 NOT NULL 列。
                    ps.setString(16, SubjectFields.normalizeType(pet.subjectType));
                    // 🔴 machine/user 两列保持可空：null = 机器没判 / 用户没答，不要 ns() 成空串，
                    //    空串会让「没答」这个状态和「答了个空」分不开，而 SR-D1 只对前者触发。
                    ps.setString(17, SubjectFields.normalizeNullableType(pet.machineSubjectType));
                    ps.setString(18, SubjectFields.normalizeNullableType(pet.userSubjectType));
                    ps.setString(19, SubjectFields.normalizeSource(pet.subjectSource));
                    ps.setString(20, GSON.toJson(pet.traits));
                    ps.setLong(21, pet.createTime);
                });
    }

    @Override
    public PetProfile petOfOwner(long accountId) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM \"t_pet\" WHERE \"ownerAccountId\" = ? ORDER BY \"createTime\" ASC LIMIT 1",
                ps -> ps.setLong(1, accountId));
        return rows.isEmpty() ? null : toPet(rows.get(0));
    }

    @Override
    public PetProfile petById(String petId) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM \"t_pet\" WHERE \"petId\" = ?",
                ps -> ps.setLong(1, lid(petId)));
        return rows.isEmpty() ? null : toPet(rows.get(0));
    }

    @Override
    public List<PetProfile> allPets() {
        List<Map<String, Object>> rows = query("SELECT * FROM \"t_pet\"", null);
        List<PetProfile> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(toPet(r));
        }
        return out;
    }

    @Override
    public void deletePet(String petId, long ownerAccountId) {
        long pid = lid(petId);
        update("DELETE FROM \"t_pet_echo\" WHERE \"petId\" = ?", ps -> ps.setLong(1, pid));
        update("DELETE FROM \"t_postcard\" WHERE \"petId\" = ?", ps -> ps.setLong(1, pid));
        update("DELETE FROM \"t_remember\" WHERE \"petId\" = ?", ps -> ps.setLong(1, pid));
        update("DELETE FROM \"t_pet\" WHERE \"petId\" = ?", ps -> ps.setLong(1, pid));
    }

    private PetProfile toPet(Map<String, Object> r) {
        PetProfile p = new PetProfile();
        p.petId = String.valueOf(asLong(r.get("petId")));
        p.ownerAccountId = asLong(r.get("ownerAccountId"));
        p.name = asStr(r.get("name"));
        p.species = asStr(r.get("species"));
        p.signature = asStr(r.get("signature"));
        p.temperature = asDouble(r.get("temperature"));
        p.visibility = asStr(r.get("visibility"));
        p.coverGradient = asStr(r.get("coverGradient"));
        p.coverEmoji = asStr(r.get("coverEmoji"));
        p.memoryCaption = asStr(r.get("memoryCaption"));
        p.lastVisitAt = asLong(r.get("lastVisitAt"));
        p.seenCount = asLong(r.get("seenCount"));
        p.flowersReceived = asLong(r.get("flowersReceived"));
        p.trainConsent = asBool(r.get("trainConsent"));
        // 🔴 读取侧也要过归一化，不能直接 asStr：老库刚补列时存量行可能是 NULL，
        //    asStr 会读成空串，而空串既不是 'other' 也不是 'animal'，会带着一个非法值往下跑。
        p.subjectType = SubjectFields.normalizeType(asStrOrNull(r.get("subjectType")));
        p.machineSubjectType = SubjectFields.normalizeNullableType(asStrOrNull(r.get("machineSubjectType")));
        p.userSubjectType = SubjectFields.normalizeNullableType(asStrOrNull(r.get("userSubjectType")));
        p.subjectSource = SubjectFields.normalizeSource(asStrOrNull(r.get("subjectSource")));
        p.createTime = asLong(r.get("createTime"));
        String lifeBook = asStr(r.get("lifeBook"));
        if (!lifeBook.isEmpty()) {
            List<LifeBookItem> items = GSON.fromJson(lifeBook, new TypeToken<List<LifeBookItem>>() { }.getType());
            if (items != null) {
                p.lifeBook.addAll(items);
            }
        }
        String traits = asStr(r.get("traits"));
        if (!traits.isEmpty()) {
            List<String> ts = GSON.fromJson(traits, new TypeToken<List<String>>() { }.getType());
            if (ts != null) {
                p.traits.addAll(ts);
            }
        }
        return p;
    }

    // ============================================================ echoes
    @Override
    public void addEcho(PetEcho e) {
        update("INSERT INTO \"t_pet_echo\" (\"echoId\",\"petId\",\"text\",\"tone\",\"reply\",\"createdAt\") "
                + "VALUES (?,?,?,?,?,?) "
                + "ON CONFLICT (\"echoId\") DO UPDATE SET "
                + "\"text\"=EXCLUDED.\"text\",\"tone\"=EXCLUDED.\"tone\",\"reply\"=EXCLUDED.\"reply\"",
                ps -> {
                    ps.setLong(1, lid(e.echoId));
                    ps.setLong(2, lid(e.petId));
                    ps.setString(3, ns(e.text));
                    ps.setString(4, ns(e.tone));
                    ps.setString(5, e.reply);
                    ps.setLong(6, e.createdAt);
                });
    }

    @Override
    public List<PetEcho> echoesOfPet(String petId) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM \"t_pet_echo\" WHERE \"petId\" = ? ORDER BY \"createdAt\" ASC",
                ps -> ps.setLong(1, lid(petId)));
        List<PetEcho> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(toEcho(r));
        }
        return out;
    }

    @Override
    public PetEcho echoById(String echoId) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM \"t_pet_echo\" WHERE \"echoId\" = ?",
                ps -> ps.setLong(1, lid(echoId)));
        return rows.isEmpty() ? null : toEcho(rows.get(0));
    }

    private PetEcho toEcho(Map<String, Object> r) {
        PetEcho e = new PetEcho();
        e.echoId = String.valueOf(asLong(r.get("echoId")));
        e.petId = String.valueOf(asLong(r.get("petId")));
        e.text = asStr(r.get("text"));
        e.tone = asStr(r.get("tone"));
        e.reply = (String) r.get("reply");
        e.createdAt = asLong(r.get("createdAt"));
        return e;
    }

    // ============================================================ flowers
    @Override
    public void addFlowerLog(FlowerLog l) {
        update("INSERT INTO \"t_flower_log\" "
                + "(\"id\",\"windowId\",\"fromAccountId\",\"toOwnerAccountId\",\"count\",\"type\",\"message\",\"anonymous\",\"day\",\"createdAt\") "
                + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                ps -> {
                    ps.setLong(1, lid(l.id));
                    ps.setLong(2, lid(l.windowId));
                    ps.setLong(3, l.fromAccountId);
                    ps.setLong(4, l.toOwnerAccountId);
                    ps.setInt(5, l.count);
                    ps.setString(6, ns(l.type));
                    ps.setString(7, ns(l.message));
                    ps.setInt(8, b2i(l.anonymous));
                    ps.setInt(9, l.day);
                    ps.setLong(10, l.createdAt);
                });
    }

    @Override
    public int flowersUsedToday(long accountId, int day) {
        List<Map<String, Object>> rows = query(
                "SELECT COALESCE(SUM(\"count\"),0) AS s FROM \"t_flower_log\" "
                        + "WHERE \"fromAccountId\" = ? AND \"day\" = ? AND \"type\" = 'daily'",
                ps -> {
                    ps.setLong(1, accountId);
                    ps.setInt(2, day);
                });
        return rows.isEmpty() ? 0 : (int) asLong(rows.get(0).get("s"));
    }

    @Override
    public int flowersFromTo(long fromAccountId, long ownerAccountId) {
        List<Map<String, Object>> rows = query(
                "SELECT COALESCE(SUM(\"count\"),0) AS s FROM \"t_flower_log\" "
                        + "WHERE \"fromAccountId\" = ? AND \"toOwnerAccountId\" = ?",
                ps -> {
                    ps.setLong(1, fromAccountId);
                    ps.setLong(2, ownerAccountId);
                });
        return rows.isEmpty() ? 0 : (int) asLong(rows.get(0).get("s"));
    }

    @Override
    public Object flowerLock() {
        return this;
    }

    // ============================================================ remember
    @Override
    public boolean setRemember(String petId, long accountId, boolean remembered) {
        try {
            if (remembered) {
                return db.update(
                        "INSERT INTO \"t_remember\" (\"id\",\"petId\",\"accountId\",\"createdAt\") VALUES (?,?,?,?) "
                                + "ON CONFLICT (\"petId\",\"accountId\") DO NOTHING",
                        ps -> {
                            ps.setLong(1, idGenerator.nextId());
                            ps.setLong(2, lid(petId));
                            ps.setLong(3, accountId);
                            ps.setLong(4, System.currentTimeMillis());
                        }) > 0;
            }
            return db.update(
                    "DELETE FROM \"t_remember\" WHERE \"petId\" = ? AND \"accountId\" = ?",
                    ps -> {
                        ps.setLong(1, lid(petId));
                        ps.setLong(2, accountId);
                    }) > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("setRemember 失败", e);
        }
    }

    @Override
    public boolean isRemembered(String petId, long accountId) {
        List<Map<String, Object>> rows = query(
                "SELECT 1 FROM \"t_remember\" WHERE \"petId\" = ? AND \"accountId\" = ? LIMIT 1",
                ps -> {
                    ps.setLong(1, lid(petId));
                    ps.setLong(2, accountId);
                });
        return !rows.isEmpty();
    }

    @Override
    public List<Long> rememberFaces(String petId) {
        List<Map<String, Object>> rows = query(
                "SELECT \"accountId\" FROM \"t_remember\" WHERE \"petId\" = ? ORDER BY \"createdAt\" ASC",
                ps -> ps.setLong(1, lid(petId)));
        List<Long> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(asLong(r.get("accountId")));
        }
        return out;
    }

    // ============================================================ 被接住（B20）
    @Override
    public List<ReactionMark> reactionsReceived(long ownerAccountId, int limit) {
        int lim = limit <= 0 ? 200 : limit;
        // 献花与记得两张表形状不同，用 UNION ALL 归一成同一种「有人来过」。
        // 🔴 两侧都排掉 actor = owner：自己给自己献花不是「被接住」。
        List<Map<String, Object>> rows = query(
                "SELECT * FROM ("
                        + "SELECT 'flower' AS \"kind\", "
                        + "CONCAT('flower:', \"id\") AS \"markId\", "
                        + "\"windowId\" AS \"wid\", \"fromAccountId\" AS \"actor\", \"createdAt\" "
                        + "FROM \"t_flower_log\" WHERE \"toOwnerAccountId\" = ? AND \"fromAccountId\" <> ? "
                        + "UNION ALL "
                        + "SELECT 'remember', "
                        // 稳定 id 取 (petId, accountId) 而非行主键：取消后重新记得是另一次回应，
                        // 但同一次记得在行主键被重建时也不该换 id，(pet, account) 唯一约束正是这个语义。
                        + "CONCAT('remember:', r.\"petId\", ':', r.\"accountId\"), "
                        + "r.\"petId\", r.\"accountId\", r.\"createdAt\" "
                        + "FROM \"t_remember\" r JOIN \"t_pet\" p ON p.\"petId\" = r.\"petId\" "
                        + "WHERE p.\"ownerAccountId\" = ? AND r.\"accountId\" <> ?"
                        + ") u ORDER BY \"createdAt\" DESC LIMIT ?",
                ps -> {
                    ps.setLong(1, ownerAccountId);
                    ps.setLong(2, ownerAccountId);
                    ps.setLong(3, ownerAccountId);
                    ps.setLong(4, ownerAccountId);
                    ps.setInt(5, lim);
                });
        List<ReactionMark> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            ReactionMark m = new ReactionMark();
            m.id = asStr(r.get("markId"));
            m.kind = asStr(r.get("kind"));
            m.windowId = String.valueOf(asLong(r.get("wid")));
            m.actorAccountId = asLong(r.get("actor"));
            m.createdAt = asLong(r.get("createdAt"));
            out.add(m);
        }
        return out;
    }

    @Override
    public void markReactionsSeen(long ownerAccountId, String windowId, long upToCreatedAt) {
        // GREATEST 保证水位只涨不退：并发的两次「看过」不会互相把对方抹回去
        update("INSERT INTO \"t_reaction_seen\" (\"ownerAccountId\",\"windowId\",\"seenAt\") VALUES (?,?,?) "
                + "ON CONFLICT (\"ownerAccountId\",\"windowId\") DO UPDATE SET "
                + "\"seenAt\" = GREATEST(\"t_reaction_seen\".\"seenAt\", EXCLUDED.\"seenAt\")",
                ps -> {
                    ps.setLong(1, ownerAccountId);
                    ps.setLong(2, lid(windowId));
                    ps.setLong(3, upToCreatedAt);
                });
    }

    @Override
    public long reactionsSeenAt(long ownerAccountId, String windowId) {
        List<Map<String, Object>> rows = query(
                "SELECT \"seenAt\" FROM \"t_reaction_seen\" WHERE \"ownerAccountId\" = ? AND \"windowId\" = ?",
                ps -> {
                    ps.setLong(1, ownerAccountId);
                    ps.setLong(2, lid(windowId));
                });
        return rows.isEmpty() ? 0L : asLong(rows.get(0).get("seenAt"));
    }

    // ============================================================ postcards
    @Override
    public void putPostcards(String petId, List<Postcard> cards) {
        // 覆盖写：事务内先删后插，避免并发/半写
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement del = c.prepareStatement("DELETE FROM \"t_postcard\" WHERE \"petId\" = ?")) {
                    del.setLong(1, lid(petId));
                    del.executeUpdate();
                }
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO \"t_postcard\" (\"id\",\"petId\",\"date\",\"caption\",\"locked\",\"unlockHint\",\"skin\",\"createdAt\") "
                                + "VALUES (?,?,?,?,?,?,?,?)")) {
                    for (Postcard pc : cards) {
                        ins.setLong(1, lid(pc.id));
                        ins.setLong(2, lid(petId));
                        ins.setString(3, ns(pc.date));
                        ins.setString(4, ns(pc.caption));
                        ins.setInt(5, b2i(pc.locked));
                        ins.setString(6, ns(pc.unlockHint));
                        ins.setString(7, ns(pc.skin));
                        ins.setLong(8, pc.createdAt);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("putPostcards 失败", e);
        }
    }

    @Override
    public List<Postcard> postcards(String petId) {
        List<Map<String, Object>> rows = query(
                // 升序：明信片墙是时间线叙事，最早一张在最上面（有意为之，见 InMemoryEchoStore#postcards）。
                // id 兜同刻的平局，与内存实现凑出同一个全序
                "SELECT * FROM \"t_postcard\" WHERE \"petId\" = ? ORDER BY \"createdAt\" ASC, \"id\" ASC",
                ps -> ps.setLong(1, lid(petId)));
        List<Postcard> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(toPostcard(r));
        }
        return out;
    }

    @Override
    public Postcard postcard(String petId, String id) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM \"t_postcard\" WHERE \"petId\" = ? AND \"id\" = ?",
                ps -> {
                    ps.setLong(1, lid(petId));
                    ps.setLong(2, lid(id));
                });
        return rows.isEmpty() ? null : toPostcard(rows.get(0));
    }

    private Postcard toPostcard(Map<String, Object> r) {
        Postcard p = new Postcard();
        p.id = String.valueOf(asLong(r.get("id")));
        p.petId = String.valueOf(asLong(r.get("petId")));
        p.date = asStr(r.get("date"));
        p.caption = asStr(r.get("caption"));
        p.locked = asBool(r.get("locked"));
        p.unlockHint = asStr(r.get("unlockHint"));
        p.skin = asStr(r.get("skin"));
        p.createdAt = asLong(r.get("createdAt"));
        return p;
    }

    // ============================================================ records
    @Override
    public void addRecord(RecordEntry r) {
        update("INSERT INTO \"t_record\" (\"id\",\"accountId\",\"scope\",\"text\",\"createdAt\") VALUES (?,?,?,?,?)",
                ps -> {
                    ps.setLong(1, lid(r.id));
                    ps.setLong(2, r.accountId);
                    ps.setString(3, ns(r.scope));
                    ps.setString(4, ns(r.text));
                    ps.setLong(5, r.createdAt);
                });
    }

    @Override
    public List<RecordEntry> records(long accountId) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM \"t_record\" WHERE \"accountId\" = ? ORDER BY \"createdAt\" ASC",
                ps -> ps.setLong(1, accountId));
        List<RecordEntry> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            RecordEntry e = new RecordEntry();
            e.id = String.valueOf(asLong(r.get("id")));
            e.accountId = asLong(r.get("accountId"));
            e.scope = asStr(r.get("scope"));
            e.text = asStr(r.get("text"));
            e.createdAt = asLong(r.get("createdAt"));
            out.add(e);
        }
        return out;
    }

    // ============================================================ messages
    @Override
    public void addMessage(MessageEntry m) {
        update("INSERT INTO \"t_message\" "
                + "(\"id\",\"accountId\",\"kind\",\"title\",\"preview\",\"read\",\"routeType\",\"routeId\",\"createdAt\") "
                + "VALUES (?,?,?,?,?,?,?,?,?)",
                ps -> {
                    ps.setLong(1, lid(m.id));
                    ps.setLong(2, m.accountId);
                    ps.setString(3, ns(m.kind));
                    ps.setString(4, ns(m.title));
                    ps.setString(5, ns(m.preview));
                    ps.setInt(6, b2i(m.read));
                    ps.setString(7, ns(m.routeType));
                    ps.setString(8, ns(m.routeId));
                    ps.setLong(9, m.createdAt);
                });
    }

    @Override
    public List<MessageEntry> messages(long accountId) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM \"t_message\" WHERE \"accountId\" = ? ORDER BY \"createdAt\" ASC",
                ps -> ps.setLong(1, accountId));
        List<MessageEntry> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            MessageEntry m = new MessageEntry();
            m.id = String.valueOf(asLong(r.get("id")));
            m.accountId = asLong(r.get("accountId"));
            m.kind = asStr(r.get("kind"));
            m.title = asStr(r.get("title"));
            m.preview = asStr(r.get("preview"));
            m.read = asBool(r.get("read"));
            m.routeType = asStr(r.get("routeType"));
            m.routeId = asStr(r.get("routeId"));
            m.createdAt = asLong(r.get("createdAt"));
            out.add(m);
        }
        return out;
    }

    @Override
    public void updateMessage(MessageEntry m) {
        update("UPDATE \"t_message\" SET \"read\" = ?, \"kind\" = ?, \"title\" = ?, \"preview\" = ?, "
                + "\"routeType\" = ?, \"routeId\" = ? WHERE \"id\" = ?",
                ps -> {
                    ps.setInt(1, b2i(m.read));
                    ps.setString(2, ns(m.kind));
                    ps.setString(3, ns(m.title));
                    ps.setString(4, ns(m.preview));
                    ps.setString(5, ns(m.routeType));
                    ps.setString(6, ns(m.routeId));
                    ps.setLong(7, lid(m.id));
                });
    }

    // ============================================================ relations
    @Override
    public void addRelation(RelationEntry r) {
        update("INSERT INTO \"t_relation\" "
                + "(\"id\",\"accountId\",\"peerAccountId\",\"peerName\",\"peerAvatar\",\"online\",\"priority\",\"mutedUntil\",\"hasUnseenReel\",\"lastActiveAt\",\"createdAt\") "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (\"accountId\",\"peerAccountId\") DO UPDATE SET "
                + "\"peerName\"=EXCLUDED.\"peerName\",\"peerAvatar\"=EXCLUDED.\"peerAvatar\",\"online\"=EXCLUDED.\"online\","
                + "\"lastActiveAt\"=EXCLUDED.\"lastActiveAt\"",
                ps -> {
                    ps.setLong(1, lid(r.id));
                    ps.setLong(2, r.accountId);
                    ps.setLong(3, r.peerAccountId);
                    ps.setString(4, ns(r.peerName));
                    ps.setString(5, ns(r.peerAvatar));
                    ps.setInt(6, b2i(r.online));
                    ps.setInt(7, b2i(r.priority));
                    ps.setLong(8, r.mutedUntil);
                    ps.setInt(9, b2i(r.hasUnseenReel));
                    ps.setLong(10, r.lastActiveAt);
                    ps.setLong(11, r.createdAt);
                });
    }

    @Override
    public List<RelationEntry> relations(long accountId) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM \"t_relation\" WHERE \"accountId\" = ? ORDER BY \"createdAt\" ASC",
                ps -> ps.setLong(1, accountId));
        List<RelationEntry> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(toRelation(r));
        }
        return out;
    }

    @Override
    public RelationEntry relation(long accountId, String relationId) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM \"t_relation\" WHERE \"accountId\" = ? AND \"id\" = ?",
                ps -> {
                    ps.setLong(1, accountId);
                    ps.setLong(2, lid(relationId));
                });
        return rows.isEmpty() ? null : toRelation(rows.get(0));
    }

    @Override
    public void updateRelation(RelationEntry r) {
        update("UPDATE \"t_relation\" SET \"priority\" = ?, \"mutedUntil\" = ?, \"hasUnseenReel\" = ?, \"online\" = ?, "
                + "\"lastActiveAt\" = ? WHERE \"id\" = ?",
                ps -> {
                    ps.setInt(1, b2i(r.priority));
                    ps.setLong(2, r.mutedUntil);
                    ps.setInt(3, b2i(r.hasUnseenReel));
                    ps.setInt(4, b2i(r.online));
                    ps.setLong(5, r.lastActiveAt);
                    ps.setLong(6, lid(r.id));
                });
    }

    private RelationEntry toRelation(Map<String, Object> r) {
        RelationEntry e = new RelationEntry();
        e.id = String.valueOf(asLong(r.get("id")));
        e.accountId = asLong(r.get("accountId"));
        e.peerAccountId = asLong(r.get("peerAccountId"));
        e.peerName = asStr(r.get("peerName"));
        e.peerAvatar = asStr(r.get("peerAvatar"));
        e.online = asBool(r.get("online"));
        e.priority = asBool(r.get("priority"));
        e.mutedUntil = asLong(r.get("mutedUntil"));
        e.hasUnseenReel = asBool(r.get("hasUnseenReel"));
        e.lastActiveAt = asLong(r.get("lastActiveAt"));
        e.createdAt = asLong(r.get("createdAt"));
        return e;
    }

    // ============================================================ spectrum（进程内）
    @Override
    public List<SpectrumNode> spectrumNodes(long accountId) {
        return spectrumNodes.computeIfAbsent(accountId, k -> new ArrayList<>());
    }

    @Override
    public List<ShadowArea> spectrumShadows(long accountId) {
        return spectrumShadows.computeIfAbsent(accountId, k -> new ArrayList<>());
    }

    // ============================================================ onboarding（进程内）
    @Override
    public void putOnboarding(Onboarding o) {
        onboardings.put(o.onboardingId, o);
    }

    @Override
    public Onboarding onboarding(String id) {
        return onboardings.get(id);
    }

    // ============================================================ JDBC 小工具
    private void update(String sql, com.echo.infra.persistence.PgStatementBinder binder) {
        try {
            db.update(sql, binder);
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 执行失败: " + sql, e);
        }
    }

    private List<Map<String, Object>> query(String sql, com.echo.infra.persistence.PgStatementBinder binder) {
        try {
            return db.query(sql, binder);
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 查询失败: " + sql, e);
        }
    }

    private static long lid(String id) {
        return (id == null || id.isEmpty()) ? 0L : Long.parseLong(id);
    }

    private static int b2i(boolean b) {
        return b ? 1 : 0;
    }

    private static String ns(String s) {
        return s == null ? "" : s;
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static boolean asBool(Object o) {
        return o instanceof Number n && n.intValue() != 0;
    }

    private static String asStr(Object o) {
        return o == null ? "" : o.toString();
    }

    /**
     * 可空文本列：{@code NULL} 读成 {@code null}，<b>不塌成空串</b>。
     *
     * <p>🔴 用在 {@code machineSubjectType}/{@code userSubjectType} 上。这两列的 {@code null}
     * 是有意义的状态（机器没判 / 用户没答），而 {@link #asStr} 会把它和「答了个空串」混成一个值——
     * {@code SR-D1} 只对前者触发，混掉之后那条兜底就判不出来了。</p>
     */
    private static String asStrOrNull(Object o) {
        return o == null ? null : o.toString();
    }
}
