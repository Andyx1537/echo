-- =============================================================================
-- 回响 (Echo) · P1 建表脚本 (PostgreSQL 方言，架构 B'：单库 PG + pgvector)
--
-- 与 com.echo.module.* 下的 Aengine 实体注解保持一致（TECH-P1 §2）。
-- Aengine JDBCRepository 在开启 DB 时会按注解和 PostgreSQL 方言自动建表/补字段，本脚本用于：
--   1) 离线初始化 / DBA 评审；
--   2) 体现 pgvector 向量列结构（向量列由 IVectorStore 通道管理，不归通用 CRUD）。
--
-- 约定：
--   - 标识符用双引号，mixedCase 列名（openId/ownerSpaceId...）大小写敏感。
--   - 主键为雪花 ID（bigint，应用层 IDGenerator 赋值，非自增）。
--   - json 字段以 text 文本存储。
--   - 被 @Cache 的列（openId / accountId / ownerSpaceId）业务上只读、不更新。
--   - 索引名带表前缀，与 Aengine Repository 自动建索引命名一致（避免同 schema 同名冲突）。
-- =============================================================================

-- Schema 版本由部署流程写入；应用启动读取 MAX(version) 并与代码期望值严格比较。
-- 新迁移只能追加更大的版本号，不得修改已经部署过的历史版本。
CREATE TABLE IF NOT EXISTS "t_schema_version" (
    "version"   bigint NOT NULL,
    "appliedAt" bigint NOT NULL,
    PRIMARY KEY ("version")
);
-- pgvector 扩展（向量相似度检索所需）
CREATE EXTENSION IF NOT EXISTS vector;

-- ----------------------------- 账号 -----------------------------------------
CREATE TABLE IF NOT EXISTS "t_account" (
    "id"         bigint      NOT NULL,
    "openId"     varchar(64) NOT NULL DEFAULT '',
    "status"     integer     NOT NULL DEFAULT 0,
    "createTime" bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX IF NOT EXISTS "t_account_uk_open_id" ON "t_account" ("openId");

-- ----------------------------- 形象 -----------------------------------------
CREATE TABLE IF NOT EXISTS "t_avatar" (
    "id"           bigint NOT NULL,
    "accountId"    bigint NOT NULL DEFAULT 0,
    "parts"        text,
    "fashionSlots" text,
    "updateTime"   bigint NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "t_avatar_idx_account_id" ON "t_avatar" ("accountId");

-- --------------------------- 意识档案 ---------------------------------------
CREATE TABLE IF NOT EXISTS "t_mind_profile" (
    "id"            bigint  NOT NULL,
    "accountId"     bigint  NOT NULL DEFAULT 0,
    "rawPrefs"      text,
    "enrichedPrefs" text,
    "vectorId"      bigint  NOT NULL DEFAULT 0,
    "version"       integer NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "t_mind_profile_idx_account_id" ON "t_mind_profile" ("accountId");

-- --------------------------- 个人向量元数据 ----------------------------------
-- 关系型元数据由 Aengine Repository 管理；"embedding" 向量本体由 IVectorStore 通道写入，
-- 不归通用 CRUD（Repository 不读写该列）。维度先用常量 768。
-- TODO: 维度可配置（不同 LLM/编码器输出维度不同）。
CREATE TABLE IF NOT EXISTS "t_self_vector" (
    "id"        bigint       NOT NULL,
    "accountId" bigint       NOT NULL DEFAULT 0,
    "dim"       integer      NOT NULL DEFAULT 0,
    "vectorRef" varchar(128) NOT NULL DEFAULT '',
    "normHash"  varchar(64)  NOT NULL DEFAULT '',
    "embedProvider" varchar(32)  NOT NULL DEFAULT 'unknown',
    "embedModel"    varchar(128) NOT NULL DEFAULT 'unknown',
    "embedVersion"  varchar(64)  NOT NULL DEFAULT 'default',
    "embeddedAt"    bigint       NOT NULL DEFAULT 0,
    "embedding" vector(768),
    PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "t_self_vector_idx_account_id" ON "t_self_vector" ("accountId");
ALTER TABLE "t_self_vector" ADD COLUMN IF NOT EXISTS "embedProvider" varchar(32) NOT NULL DEFAULT 'unknown';
ALTER TABLE "t_self_vector" ADD COLUMN IF NOT EXISTS "embedModel" varchar(128) NOT NULL DEFAULT 'unknown';
ALTER TABLE "t_self_vector" ADD COLUMN IF NOT EXISTS "embedVersion" varchar(64) NOT NULL DEFAULT 'default';
ALTER TABLE "t_self_vector" ADD COLUMN IF NOT EXISTS "embeddedAt" bigint NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS "t_self_vector_idx_model_version"
    ON "t_self_vector" ("embedProvider", "embedModel", "embedVersion");
-- HNSW 无需训练即可增量维护，适合当前持续写入；查询仍按模型身份过滤，禁止跨模型比较。
CREATE INDEX IF NOT EXISTS "t_self_vector_embedding_hnsw" ON "t_self_vector"
    USING hnsw ("embedding" vector_cosine_ops) WITH (m = 16, ef_construction = 64);

-- --------------------------- 意识空间实例 ------------------------------------
CREATE TABLE IF NOT EXISTS "t_mind_space" (
    "id"            bigint NOT NULL,
    "accountId"     bigint NOT NULL DEFAULT 0,
    "presetSetId"   bigint NOT NULL DEFAULT 0,
    "dynamicParams" text,
    "hostConfig"    text,
    "updateTime"    bigint NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "t_mind_space_idx_account_id" ON "t_mind_space" ("accountId");

-- --------------------------- 共鸣记录 ---------------------------------------
CREATE TABLE IF NOT EXISTS "t_resonance_record" (
    "id"         bigint           NOT NULL,
    "accountId"  bigint           NOT NULL DEFAULT 0,
    "peerId"     bigint           NOT NULL DEFAULT 0,
    "score"      double precision NOT NULL DEFAULT 0,
    "createTime" bigint           NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "t_resonance_record_idx_account_id" ON "t_resonance_record" ("accountId");

-- ----------------------------- 回声 -----------------------------------------
CREATE TABLE IF NOT EXISTS "t_echo" (
    "id"            bigint NOT NULL,
    "ownerSpaceId"  bigint NOT NULL DEFAULT 0,
    "fromAccountId" bigint NOT NULL DEFAULT 0,
    "payload"       text,
    "expireAt"      bigint NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "t_echo_idx_owner_space_id" ON "t_echo" ("ownerSpaceId");
CREATE INDEX IF NOT EXISTS "t_echo_idx_expire_at" ON "t_echo" ("expireAt");

-- --------------------------- 关系链 -----------------------------------------
CREATE TABLE IF NOT EXISTS "t_friendship" (
    "id"         bigint  NOT NULL,
    "accountId"  bigint  NOT NULL DEFAULT 0,
    "peerId"     bigint  NOT NULL DEFAULT 0,
    "type"       integer NOT NULL DEFAULT 0,
    "createTime" bigint  NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX IF NOT EXISTS "t_friendship_uk_account_peer" ON "t_friendship" ("accountId", "peerId");
CREATE INDEX IF NOT EXISTS "t_friendship_idx_account_id" ON "t_friendship" ("accountId");

-- ----------------------------- 摊位 -----------------------------------------
CREATE TABLE IF NOT EXISTS "t_stall" (
    "id"             bigint  NOT NULL,
    "accountId"      bigint  NOT NULL DEFAULT 0,
    "spaceId"        bigint  NOT NULL DEFAULT 0,
    "displayPayload" text,
    "status"         integer NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "t_stall_idx_account_id" ON "t_stall" ("accountId");

-- =============================================================================
-- HTTP/JSON REST 网关新增域（H5 完整落地版，API-CONTRACT v1 §1-§11）
--
-- 说明：EchoStore 已抽象为接口，双实现——
--   - DB 关：com.echo.http.store.InMemoryEchoStore（无 DB 跑通核心闭环 / 单测无需起库）；
--   - DB 开：com.echo.http.store.PgEchoStore（落库耐久域，构造时按下列 DDL 幂等建表）。
-- 下列建表脚本与 com.echo.http.model.Models 字段一一对齐，既是 DBA 评审真源，也与 PgEchoStore
-- 内嵌 DDL 一致。字段命名沿用 mixedCase + 双引号约定。
--
-- 六项定案在服务端强制（不依赖表约束，见 EchoApi）；表结构上刻意"不设总数/排名列"：
--   - t_flower_log 记流水，不在宠物上加温度列（定案 #5 温度与献花解耦）；
--   - t_remember 为状态开关（accountId+petId 唯一），不做累计计数（定案 #4 无数字）。
-- =============================================================================

-- --------------------------- 账号 HTTP 概要 ---------------------------------
-- 游客一等公民：deviceId 幂等；visibilityDefault 默认 private（定案 #1）。
CREATE TABLE IF NOT EXISTS "t_account_profile" (
    "accountId"         bigint       NOT NULL,
    "deviceId"          varchar(128) NOT NULL DEFAULT '',
    "guest"             smallint     NOT NULL DEFAULT 1,
    "nickname"          varchar(64)  NOT NULL DEFAULT '',
    "avatar"            varchar(256) NOT NULL DEFAULT '',
    "visibilityDefault" varchar(16)  NOT NULL DEFAULT 'private',
    "hasPet"            smallint     NOT NULL DEFAULT 0,
    -- 训练用途同意（PIPL 独立 opt-in，默认 0/未同意；与纪念场景 allowUse 分开）
    "trainConsent"      smallint     NOT NULL DEFAULT 0,
    "createTime"        bigint       NOT NULL DEFAULT 0,
    PRIMARY KEY ("accountId")
);
CREATE UNIQUE INDEX IF NOT EXISTS "t_account_profile_uk_device" ON "t_account_profile" ("deviceId");
-- 已有账号表补列（幂等）：
ALTER TABLE "t_account_profile" ADD COLUMN IF NOT EXISTS "trainConsent" smallint NOT NULL DEFAULT 0;

-- ----------------------------- 持久身份 -------------------------------------
-- phone-account-resolution-v1：数据库只保存登录秘密的 HMAC 摘要；登录会话和
-- 设备凭据没有自然到期，仅由绑定、切号、退出或治理显式撤销。
CREATE TABLE IF NOT EXISTS "t_auth_session" (
    "sessionId" varchar(64) NOT NULL,
    "tokenHash" varchar(64) NOT NULL,
    "accountId" bigint NOT NULL,
    "kind" varchar(16) NOT NULL,
    "status" varchar(16) NOT NULL DEFAULT 'active',
    "deviceCredentialId" varchar(64),
    "createdAt" bigint NOT NULL,
    "revokedAt" bigint,
    PRIMARY KEY ("sessionId")
);
CREATE UNIQUE INDEX IF NOT EXISTS "t_auth_session_uk_token" ON "t_auth_session" ("tokenHash");
CREATE INDEX IF NOT EXISTS "t_auth_session_idx_account_status" ON "t_auth_session" ("accountId", "status");

CREATE TABLE IF NOT EXISTS "t_device_credential" (
    "credentialId" varchar(64) NOT NULL,
    "credentialHash" varchar(64) NOT NULL,
    "accountId" bigint NOT NULL,
    "status" varchar(24) NOT NULL,
    "revocationReason" varchar(32),
    "createdAt" bigint NOT NULL,
    "updatedAt" bigint NOT NULL,
    PRIMARY KEY ("credentialId")
);
CREATE UNIQUE INDEX IF NOT EXISTS "t_device_credential_uk_hash" ON "t_device_credential" ("credentialHash");
CREATE INDEX IF NOT EXISTS "t_device_credential_idx_account_status" ON "t_device_credential" ("accountId", "status");

CREATE TABLE IF NOT EXISTS "t_phone_credential" (
    "phoneHash" varchar(64) NOT NULL,
    "phoneCipher" text NOT NULL,
    "accountId" bigint NOT NULL,
    "createdAt" bigint NOT NULL,
    PRIMARY KEY ("phoneHash")
);
CREATE UNIQUE INDEX IF NOT EXISTS "t_phone_credential_uk_account" ON "t_phone_credential" ("accountId");

CREATE TABLE IF NOT EXISTS "t_phone_challenge" (
    "challengeId" varchar(64) NOT NULL,
    "accountId" bigint NOT NULL,
    "sessionId" varchar(64) NOT NULL,
    "phoneHash" varchar(64) NOT NULL,
    "phoneCipher" text NOT NULL,
    "codeHash" varchar(64) NOT NULL,
    "purpose" varchar(32) NOT NULL,
    "continuationIntent" varchar(48) NOT NULL,
    "resourceId" varchar(64),
    "schemaVersion" varchar(16),
    "status" varchar(16) NOT NULL,
    "attemptCount" integer NOT NULL DEFAULT 0,
    "expiresAt" bigint NOT NULL,
    "resendAvailableAt" bigint NOT NULL,
    "createdAt" bigint NOT NULL,
    PRIMARY KEY ("challengeId")
);
CREATE INDEX IF NOT EXISTS "t_phone_challenge_idx_phone_purpose" ON "t_phone_challenge" ("phoneHash", "purpose", "createdAt" DESC);

CREATE TABLE IF NOT EXISTS "t_phone_resolution" (
    "resolutionId" varchar(64) NOT NULL,
    "tokenHash" varchar(64) NOT NULL,
    "challengeId" varchar(64) NOT NULL,
    "sourceAccountId" bigint NOT NULL,
    "sourceSessionId" varchar(64) NOT NULL,
    "resolution" varchar(24) NOT NULL,
    "targetAccountId" bigint,
    "phoneHash" varchar(64) NOT NULL,
    "phoneCipher" text NOT NULL,
    "continuationIntent" varchar(48) NOT NULL,
    "resourceId" varchar(64),
    "schemaVersion" varchar(16),
    "status" varchar(16) NOT NULL,
    "expiresAt" bigint NOT NULL,
    "usedAt" bigint,
    PRIMARY KEY ("resolutionId")
);
CREATE UNIQUE INDEX IF NOT EXISTS "t_phone_resolution_uk_token" ON "t_phone_resolution" ("tokenHash");
CREATE UNIQUE INDEX IF NOT EXISTS "t_phone_resolution_uk_challenge" ON "t_phone_resolution" ("challengeId");

CREATE TABLE IF NOT EXISTS "t_auth_idempotency" (
    "operation" varchar(48) NOT NULL,
    "actorScope" varchar(128) NOT NULL,
    "idempotencyKey" varchar(128) NOT NULL,
    "requestHash" varchar(64) NOT NULL,
    "responseCipher" text,
    "status" varchar(16) NOT NULL DEFAULT 'processing',
    "replayUntil" bigint NOT NULL,
    "resultSessionId" varchar(64),
    "resultDeviceCredentialId" varchar(64),
    "createdAt" bigint NOT NULL,
    "updatedAt" bigint NOT NULL,
    PRIMARY KEY ("operation", "actorScope", "idempotencyKey")
);
ALTER TABLE "t_auth_idempotency" ADD COLUMN IF NOT EXISTS "replayUntil" bigint NOT NULL DEFAULT 0;
ALTER TABLE "t_auth_idempotency" ADD COLUMN IF NOT EXISTS "resultSessionId" varchar(64);
ALTER TABLE "t_auth_idempotency" ADD COLUMN IF NOT EXISTS "resultDeviceCredentialId" varchar(64);
CREATE INDEX IF NOT EXISTS "t_auth_idempotency_idx_created" ON "t_auth_idempotency" ("createdAt");

CREATE TABLE IF NOT EXISTS "t_auth_rate_event" (
    "eventId" varchar(64) NOT NULL,
    "dimension" varchar(16) NOT NULL,
    "subjectHash" varchar(64) NOT NULL,
    "createdAt" bigint NOT NULL,
    PRIMARY KEY ("eventId")
);
CREATE INDEX IF NOT EXISTS "t_auth_rate_event_idx_window" ON "t_auth_rate_event" ("dimension", "subjectHash", "createdAt");

CREATE TABLE IF NOT EXISTS "t_auth_audit" (
    "auditId" varchar(64) NOT NULL,
    "eventType" varchar(48) NOT NULL,
    "accountId" bigint,
    "sessionId" varchar(64),
    "subjectHash" varchar(64),
    "detail" text,
    "createdAt" bigint NOT NULL,
    PRIMARY KEY ("auditId")
);
CREATE INDEX IF NOT EXISTS "t_auth_audit_idx_account_time" ON "t_auth_audit" ("accountId", "createdAt" DESC);

-- ----------------------------- 往宠档案 -------------------------------------
-- temperature 只由主人回访驱动、地板 60（PRD §3.11）；seenCount/flowersReceived 仅 owner 私域可见。
CREATE TABLE IF NOT EXISTS "t_pet" (
    "petId"           bigint           NOT NULL,
    "ownerAccountId"  bigint           NOT NULL DEFAULT 0,
    "name"            varchar(64)      NOT NULL DEFAULT '',
    "species"         varchar(64)      NOT NULL DEFAULT '',
    "signature"       varchar(256)     NOT NULL DEFAULT '',
    "temperature"     double precision NOT NULL DEFAULT 72,
    "visibility"      varchar(16)      NOT NULL DEFAULT 'private',
    "coverGradient"   varchar(64)      NOT NULL DEFAULT '',
    "coverEmoji"      varchar(16)      NOT NULL DEFAULT '',
    "memoryCaption"   text,
    "lifeBook"        text,
    "lastVisitAt"     bigint           NOT NULL DEFAULT 0,
    "seenCount"       bigint           NOT NULL DEFAULT 0,
    "flowersReceived" bigint           NOT NULL DEFAULT 0,
    -- 训练用途同意（随建档确认从账号/建档态带入，默认 0/未同意；PIPL 门控用）
    "trainConsent"    smallint         NOT NULL DEFAULT 0,
    -- 建档期主体类型四字段（随 /pet/onboarding/confirm 从建档态带入）。
    -- 🔴 用途是让 SR-D1 能在服务端求值，不是给展示用的：SR-D1 的触发条件是
    --    「素材置信度低 且 subjectSource != 'user'」，后半句的判据就是 subjectSource 这一列。
    --    🔴 这四列一律不下发到响应体（COPY-GUIDE 禁止对外暴露主体识别的判定结果）。
    -- 🔴 默认值是 'other'/'default' 而不是 'animal'/'user'：见 Models.SubjectFields。
    --    'animal' 是能力最宽的一类，拿它兜底等于「认不出就按最宽的放行」；
    --    subjectSource 兜底成 'user' 更糟——它会静默关掉 SR-D1 整条兜底分支。
    -- machineSubjectType / userSubjectType 可空：NULL = 机器没判 / 用户没动过预填值，
    --    🔴 这是有意义的状态，不要用空串代替（空串会让「没答」和「答了个空」分不开）。
    "subjectType"        varchar(16)   NOT NULL DEFAULT 'other',
    "machineSubjectType" varchar(16),
    "userSubjectType"    varchar(16),
    "subjectSource"      varchar(16)   NOT NULL DEFAULT 'default',
    "traits"          text,
    "createTime"      bigint           NOT NULL DEFAULT 0,
    PRIMARY KEY ("petId")
);
CREATE INDEX IF NOT EXISTS "t_pet_idx_owner" ON "t_pet" ("ownerAccountId");
CREATE INDEX IF NOT EXISTS "t_pet_idx_visibility" ON "t_pet" ("visibility");
-- 已有宠物表补列（幂等）：
ALTER TABLE "t_pet" ADD COLUMN IF NOT EXISTS "trainConsent" smallint NOT NULL DEFAULT 0;
-- 🔴 存量行落 'other'/'default'，与 SR-D2「只管新上传、存量不回溯」同向：
--    存量行从未被判定过，而这两个兜底值正是「没判过」该有的值。
ALTER TABLE "t_pet" ADD COLUMN IF NOT EXISTS "subjectType" varchar(16) NOT NULL DEFAULT 'other';
ALTER TABLE "t_pet" ADD COLUMN IF NOT EXISTS "machineSubjectType" varchar(16);
ALTER TABLE "t_pet" ADD COLUMN IF NOT EXISTS "userSubjectType" varchar(16);
ALTER TABLE "t_pet" ADD COLUMN IF NOT EXISTS "subjectSource" varchar(16) NOT NULL DEFAULT 'default';

-- --------------------------- 近况/来信（AI 生成） ---------------------------
CREATE TABLE IF NOT EXISTS "t_pet_echo" (
    "echoId"    bigint       NOT NULL,
    "petId"     bigint       NOT NULL DEFAULT 0,
    "text"      text,
    "tone"      varchar(32)  NOT NULL DEFAULT 'gentle',
    "reply"     text,
    "createdAt" bigint       NOT NULL DEFAULT 0,
    PRIMARY KEY ("echoId")
);
CREATE INDEX IF NOT EXISTS "t_pet_echo_idx_pet" ON "t_pet_echo" ("petId");

-- ----------------------------- 献花流水 -------------------------------------
-- 定案 #3：每日 5 朵/可买/不加温/不排名；owner 私密羁绊名单。day=yyyyMMdd 供每日额度核算。
CREATE TABLE IF NOT EXISTS "t_flower_log" (
    "id"               bigint       NOT NULL,
    "windowId"         bigint       NOT NULL DEFAULT 0,
    "fromAccountId"    bigint       NOT NULL DEFAULT 0,
    "toOwnerAccountId" bigint       NOT NULL DEFAULT 0,
    "count"            integer      NOT NULL DEFAULT 0,
    "type"             varchar(16)  NOT NULL DEFAULT 'daily',
    "message"          text,
    "anonymous"        smallint     NOT NULL DEFAULT 0,
    "day"              integer      NOT NULL DEFAULT 0,
    "createdAt"        bigint       NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "t_flower_log_idx_from_day" ON "t_flower_log" ("fromAccountId", "day");
CREATE INDEX IF NOT EXISTS "t_flower_log_idx_owner" ON "t_flower_log" ("toOwnerAccountId");

-- ------------------------------- 记得 ---------------------------------------
-- 定案 #4：一人一次的状态开关（非累计计数）；(petId, accountId) 唯一。精确总数不对外。
CREATE TABLE IF NOT EXISTS "t_remember" (
    "id"        bigint NOT NULL,
    "petId"     bigint NOT NULL DEFAULT 0,
    "accountId" bigint NOT NULL DEFAULT 0,
    "createdAt" bigint NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX IF NOT EXISTS "t_remember_uk_pet_account" ON "t_remember" ("petId", "accountId");

-- --------------------- 被接住的到达 · 已看水位（B20/D22）-----------------------
-- 存一条水位线，而不是每条回应一个已读位：「看过即散」（B23）散的是整张卡的暖点，
-- 不存在「读了一半」这种状态。水位天然单调不回退，逐行已读位则可被改回未读——
-- 那等于给红点轰炸留了一条路。
CREATE TABLE IF NOT EXISTS "t_reaction_seen" (
    "ownerAccountId" bigint NOT NULL,
    "windowId"       bigint NOT NULL,
    "seenAt"         bigint NOT NULL DEFAULT 0,
    PRIMARY KEY ("ownerAccountId", "windowId")
);

-- ----------------------------- 明信片 ---------------------------------------
-- 定案 #2：内容靠陪伴解锁，付费只加速/款式；skin 记款式，不影响 locked。
CREATE TABLE IF NOT EXISTS "t_postcard" (
    "id"         bigint       NOT NULL,
    "petId"      bigint       NOT NULL DEFAULT 0,
    "date"       varchar(32)  NOT NULL DEFAULT '',
    "caption"    text,
    "locked"     smallint     NOT NULL DEFAULT 1,
    "unlockHint" varchar(128) NOT NULL DEFAULT '',
    "skin"       varchar(64)  NOT NULL DEFAULT 'classic',
    "createdAt"  bigint       NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "t_postcard_idx_pet" ON "t_postcard" ("petId");

-- ------------------------------- 记录 ---------------------------------------
-- 非打卡任务：不设连续天数/红点列。scope=pet|self。
CREATE TABLE IF NOT EXISTS "t_record" (
    "id"        bigint      NOT NULL,
    "accountId" bigint      NOT NULL DEFAULT 0,
    "scope"     varchar(16) NOT NULL DEFAULT 'self',
    "text"      text,
    "createdAt" bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "t_record_idx_account" ON "t_record" ("accountId");

-- ------------------------------- 消息 ---------------------------------------
CREATE TABLE IF NOT EXISTS "t_message" (
    "id"        bigint       NOT NULL,
    "accountId" bigint       NOT NULL DEFAULT 0,
    "kind"      varchar(16)  NOT NULL DEFAULT 'system',
    "title"     varchar(128) NOT NULL DEFAULT '',
    "preview"   text,
    "read"      smallint     NOT NULL DEFAULT 0,
    "routeType" varchar(16)  NOT NULL DEFAULT '',
    "routeId"   varchar(64)  NOT NULL DEFAULT '',
    "createdAt" bigint       NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "t_message_idx_account" ON "t_message" ("accountId");

-- ------------------------------- 亲友 ---------------------------------------
CREATE TABLE IF NOT EXISTS "t_relation" (
    "id"             bigint       NOT NULL,
    "accountId"      bigint       NOT NULL DEFAULT 0,
    "peerAccountId"  bigint       NOT NULL DEFAULT 0,
    "peerName"       varchar(64)  NOT NULL DEFAULT '',
    "peerAvatar"     varchar(256) NOT NULL DEFAULT '',
    "online"         smallint     NOT NULL DEFAULT 0,
    "priority"       smallint     NOT NULL DEFAULT 0,
    "mutedUntil"     bigint       NOT NULL DEFAULT 0,
    "hasUnseenReel"  smallint     NOT NULL DEFAULT 0,
    "lastActiveAt"   bigint       NOT NULL DEFAULT 0,
    "createdAt"      bigint       NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX IF NOT EXISTS "t_relation_uk_account_peer" ON "t_relation" ("accountId", "peerAccountId");
CREATE INDEX IF NOT EXISTS "t_relation_idx_account" ON "t_relation" ("accountId");

-- =============================================================================
-- 训练语料回流（AI-CAPABILITIES §7）
--
-- 说明：接云 LLM 的同时，把「原始素材 ref + 识别/纠正 + 定妆所选 + 性情词 + 回声文本 + 用户反馈」
-- 沉淀为自养模型（F/G 回声·基调、A 识别、B 定妆）的训练样本。
--
-- PIPL 合规红线（硬性）：
--   - 训练用途必须独立 opt-in（t_account_profile.trainConsent / t_pet.trainConsent），
--     与建档纪念场景 allowUse 分开；仅 consent=1 才写入本表（应用层门控，见 EchoApi + ITrainingCorpus）。
--   - 去标识：accountId 存 hash（不落真实账号身份），仅留内容与反馈信号。
--   - 可撤回 + 可删除：撤回同意后同步剔除对应样本（TODO：撤回清理任务）。
--
-- 本期为内存态实现（com.echo.infra.corpus.InMemoryTrainingCorpus）；PG 落库为 TODO，
-- 本表结构与 com.echo.infra.corpus.TrainSample 字段一一对齐，作为 DBA 评审与落库真源。
-- =============================================================================
CREATE TABLE IF NOT EXISTS "t_train_sample" (
    "id"                 bigint       NOT NULL,
    -- 去标识后的账号 hash（PIPL：不存真实 accountId）
    "accountId"          varchar(64)  NOT NULL DEFAULT '',
    "petId"              bigint       NOT NULL DEFAULT 0,
    -- 输入素材 resourceId 列表（json 文本；原件在私有对象存储，语料只留引用）
    "inputRefs"          text,
    "detectedSpecies"    varchar(64)  NOT NULL DEFAULT '',
    "correctedSpecies"   varchar(64)  NOT NULL DEFAULT '',
    "chosenCandidateId"  varchar(64)  NOT NULL DEFAULT '',
    "redoCount"          integer      NOT NULL DEFAULT 0,
    -- 性情词（json 文本）
    "traits"             text,
    "echoText"           text,
    -- 用户反馈信号：remember|flower|stay
    "feedback"           varchar(32)  NOT NULL DEFAULT '',
    "consent"            smallint     NOT NULL DEFAULT 0,
    "createdAt"          bigint       NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "t_train_sample_idx_pet" ON "t_train_sample" ("petId");
CREATE INDEX IF NOT EXISTS "t_train_sample_idx_consent" ON "t_train_sample" ("consent");

-- =============================================================================
-- 回忆卡发布 / 回声 / 审核后台 / 曝光记账
--   规格真源：SPEC-publish-and-ops.md v0.6 §1.8（数据模型补齐）、§2.6/§2.6.1（审核与申诉）
--             API-CONTRACT.md v1.4 §15.4（t_audit_log）、§17（审核 / 申诉 / 举报契约）
--             TECH-DESIGN-feed-recall-and-exposure.md v0.5 §3.8（曝光记账）
--
-- 实现约定（沿 §1.8，与本文件上半部分一致）：
--   - 标识符双引号 + mixedCase；主键为雪花 ID（bigint，应用层 IDGenerator 赋值，非自增）。
--   - 🔴 时间列一律 bigint（UTC 毫秒 epoch），全库不使用 timestamptz。
--   - 🔴 一切外键 ON DELETE RESTRICT，禁止 ON DELETE CASCADE（DECISIONS CM-D1 禁止级联清理）。
--   - 软删三列统一为 deletedAt bigint / deletedBy bigint / deleteReason varchar(64)（G0-1）。
--   - json 列用 jsonb：本段各规格字段表（§1.5 topicIds/interaction、§2.6 autoSignals/snapshot、
--     §15.4 scope、§1.8.2 beforeRow/afterRow）写的都是 jsonb，且 §1.8.2 触发器依赖 to_jsonb()。
--     本文件上半部分「json 以 text 存」的约定源自 Repository 的注解序列化，本段各表不由
--     Repository 管理（手写 SQL 访问），故不适用。
--
-- 🔴 Aengine Repository 按实体注解自动建表/补字段，不会生成 CHECK 约束、外键与触发器（§1.8.1 实现注意）。
--    本段的护栏必须由本脚本显式建立并纳入 DEPLOY 检查项，否则线上是一批没有任何护栏的裸表。
--
-- ⚠️ 建表顺序按依赖排（§1.8 已警告"按重要性排版会照抄失败"）：
--    t_account 补列 → t_topic → t_memory_card → t_resonance_type(+种子) → t_resonance
--    → t_card_visibility_log → t_moderation → t_audit_log → t_card_exposure
-- =============================================================================

-- ------------------- t_account 补列：accountType（§1.8.4 · R-16） --------------
-- 没有这一列，平台兜底回应与真实用户回应在数据上不可区分，北极星就是一个我们自己能刷满的数字。
-- 默认值方向是刻意的：账号绝大多数是真人 → DEFAULT 'user'；
-- 而 t_resonance.actorType 刻意不给默认值（见下），漏写则数据库报错而不是悄悄落成 'user'。
ALTER TABLE "t_account" ADD COLUMN IF NOT EXISTS
    "accountType" varchar(8) NOT NULL DEFAULT 'user';

ALTER TABLE "t_account" DROP CONSTRAINT IF EXISTS "t_account_ck_account_type";
ALTER TABLE "t_account" ADD  CONSTRAINT "t_account_ck_account_type"
    CHECK ("accountType" IN ('user','ops','system'));

-- 后台需能一键列出全部非真实用户账号供核查（应当是很短的一张表）
CREATE INDEX IF NOT EXISTS "t_account_idx_account_type"
    ON "t_account" ("accountType") WHERE "accountType" <> 'user';

-- ---------------------------- 主题池（§3.2） ---------------------------------
-- ⚠️ 本表规格里只有 §3.2 的一行字段列表，没有正式 DDL；此处按该行落库。
--    t_resonance_fk_topic 指向它（§1.8 建表顺序要求它先就位）。
CREATE TABLE IF NOT EXISTS "t_topic" (
    "id"        bigint       NOT NULL,
    "name"      varchar(64)  NOT NULL DEFAULT '',
    "slug"      varchar(64)  NOT NULL DEFAULT '',
    "desc"      text,
    "coverKey"  varchar(256) NOT NULL DEFAULT '',
    "status"    varchar(8)   NOT NULL DEFAULT 'on',
    "sort"      integer      NOT NULL DEFAULT 0,
    "startAt"   bigint,
    "endAt"     bigint,
    "createdAt" bigint       NOT NULL DEFAULT 0,
    PRIMARY KEY ("id"),
    CONSTRAINT "t_topic_ck_status" CHECK ("status" IN ('on','off'))
);
CREATE UNIQUE INDEX IF NOT EXISTS "t_topic_uk_slug" ON "t_topic" ("slug");
CREATE INDEX IF NOT EXISTS "t_topic_idx_status_sort" ON "t_topic" ("status", "sort");

-- --------------------------- 回忆卡（§1.5 + §1.8.3 + §1.8.3b） ----------------
-- ⚠️ 规格 §1.8.3 / §1.8.3b 写的是 ALTER TABLE 补列，前提是本表已存在——但它此前不在本文件里，
--    正式 DDL 也没有任何规格给过。此处按 §1.5 字段表落基表，补列部分照 §1.8.3/§1.8.3b 原文。
CREATE TABLE IF NOT EXISTS "t_memory_card" (
    "id"          bigint       NOT NULL,
    "ownerId"     bigint       NOT NULL DEFAULT 0,        -- 作者（含游客账号）
    "petId"       bigint       NOT NULL DEFAULT 0,        -- 关联回忆集
    "sourceType"  varchar(16)  NOT NULL DEFAULT '',       -- record | book_page | postcard | echo
    "sourceRef"   varchar(64)  NOT NULL DEFAULT '',       -- 原素材引用 id
    "coverKey"    varchar(256) NOT NULL DEFAULT '',
    "title"       varchar(64)  NOT NULL DEFAULT '',       -- ≤30 字（长度在应用层校验）
    "body"        text,                                   -- ≤500 字，入库前过《温柔词表》
    "topicIds"    jsonb,                                  -- 主题标签 id 数组（0–3）
    "visibility"  varchar(16)  NOT NULL DEFAULT 'private', -- private | friends | public（默认私密 = D2）
    "status"      varchar(16)  NOT NULL DEFAULT 'draft',
    "interaction" jsonb,                                  -- {remember,heart,echo} 作者互动开关
    "createdAt"   bigint       NOT NULL DEFAULT 0,
    "updatedAt"   bigint       NOT NULL DEFAULT 0,
    "publishedAt" bigint,                                 -- 作者点发布的时刻（≠ reviewedAt，见 §1.8.3）
    PRIMARY KEY ("id"),
    CONSTRAINT "t_memory_card_ck_visibility"
        CHECK ("visibility" IN ('private','friends','public')),
    CONSTRAINT "t_memory_card_ck_status"
        CHECK ("status" IN ('draft','active','pending','blocked','public',
                            'rejected','takendown','appealing','deleted')),
    CONSTRAINT "t_memory_card_fk_owner" FOREIGN KEY ("ownerId")
        REFERENCES "t_account" ("id") ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS "t_memory_card_idx_owner" ON "t_memory_card" ("ownerId");
CREATE INDEX IF NOT EXISTS "t_memory_card_idx_status" ON "t_memory_card" ("status");
CREATE INDEX IF NOT EXISTS "t_memory_card_idx_pet" ON "t_memory_card" ("petId");

-- §1.8.3（R-14）：reviewedAt = 卡片首次进入 status='public' 的时刻，即内容真正可被他人看见的
-- 那一刻，是北极星 7 天窗口的起点。取代 publishedAt——压在审核队列里的时间对任何人都不可见，
-- 不可能被接住，用 publishedAt 等于把审核积压时长白扣在产品头上。两者并存、各有其用，不要合并。
ALTER TABLE "t_memory_card" ADD COLUMN IF NOT EXISTS "reviewedAt"    bigint;      -- 首次过审时刻，只写一次
ALTER TABLE "t_memory_card" ADD COLUMN IF NOT EXISTS "deletedAt"     bigint;
ALTER TABLE "t_memory_card" ADD COLUMN IF NOT EXISTS "deletedBy"     bigint;
ALTER TABLE "t_memory_card" ADD COLUMN IF NOT EXISTS "deleteReason"  varchar(64);

-- 置顶（作用域 = 作者自己那一页的公开卡列表；🔴 绝不进共鸣厅公开流，那会和权重衰减正面打架）
-- 口径在 com.echo.http.card.PinPolicy：上限 3 张（后台可配）、仅 public 且已过审可置顶、
-- 取消发布/审核打回/下架时自动解除。
-- 🔴 可空是语义的一部分：NULL = 未置顶，排序里靠 NULLS LAST 落到所有置顶卡之后。
--    不要给它 DEFAULT 0 —— 0 既是"未置顶"又是一个合法时刻，两种含义混在一列里，
--    而 ORDER BY 会把全部卡都当成"置顶于 1970 年"，NULLS LAST 那一段就此失效。
ALTER TABLE "t_memory_card" ADD COLUMN IF NOT EXISTS "pinnedAt"      bigint;

-- 支撑作者主页列表 ORDER BY "pinnedAt" DESC NULLS LAST, "publishedAt" DESC
-- partial 谓词只取已置顶的行：置顶最多 3 张/人，这是一张极小的索引
CREATE INDEX IF NOT EXISTS "t_memory_card_idx_owner_pinned"
    ON "t_memory_card" ("ownerId", "pinnedAt" DESC) WHERE "pinnedAt" IS NOT NULL;

-- 🔴 只写一次、写后不可变（写在触发器里，不靠应用层自觉）
CREATE OR REPLACE FUNCTION "t_memory_card_guard_reviewed_at"() RETURNS trigger AS $$
BEGIN
    -- 用 IS DISTINCT FROM 而不是 <>：否则"把 reviewedAt 改成 NULL"会因三值逻辑而漏过
    IF OLD."reviewedAt" IS NOT NULL
       AND NEW."reviewedAt" IS DISTINCT FROM OLD."reviewedAt" THEN
        RAISE EXCEPTION 't_memory_card.reviewedAt 首次写入后不可变更（% -> %）：'
                        '它是北极星 7 天窗口的起点，改它等于给这张卡续窗口期',
                        OLD."reviewedAt", NEW."reviewedAt";
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS "t_memory_card_trg_reviewed_at" ON "t_memory_card";
CREATE TRIGGER "t_memory_card_trg_reviewed_at"
    BEFORE UPDATE ON "t_memory_card"
    FOR EACH ROW EXECUTE FUNCTION "t_memory_card_guard_reviewed_at"();

-- 支撑 eligible_cards CTE：按 reviewedAt 取区间
-- 🔴 partial 谓词只能用 reviewedAt IS NOT NULL，不要写进 visibility='public'：分母问的是"这张卡
--    当时是否曾公开过"，而不是"现在是否公开"。窗口结束之后才被作者收回的卡必须仍留在历史分母里；
--    谓词一带上 visibility 它就从索引里消失，上个月的北极星会在这个月悄悄变高。
CREATE INDEX IF NOT EXISTS "t_memory_card_idx_reviewed_at"
    ON "t_memory_card" ("reviewedAt") WHERE "reviewedAt" IS NOT NULL;

CREATE INDEX IF NOT EXISTS "t_memory_card_idx_owner_reviewed"
    ON "t_memory_card" ("ownerId", "reviewedAt") WHERE "reviewedAt" IS NOT NULL;  -- 续发率、作者维度

-- §1.8.3b（前置闸门 G-1）：内容来源。🔴 无默认值——新增发布路径必须显式声明来源，漏写则报错。
-- 官方号内容不进北极星分母靠的是正向白名单 originType='user'，不是"只从分子剔掉"。
-- 种子期官方号有一批稳定高质量内容而真人卡还很少，混进同一口径会系统性拉高北极星，
-- 正好掩盖住"真人发的卡没人理"这件唯一值得看的事。
ALTER TABLE "t_memory_card" ADD COLUMN IF NOT EXISTS "originType"    varchar(16);
-- 运营协助但署名用户的共创内容（GTM5）。它仍算 user，只是可拆分观测。
ALTER TABLE "t_memory_card" ADD COLUMN IF NOT EXISTS "assistedByOps" boolean NOT NULL DEFAULT false;

-- 🔴 置 NOT NULL —— 这一步不能省，否则「无默认值 → 漏写则报错」只是一句话：列可空时漏写
--    originType 会静默落 NULL，卡照样发得出去，而 NULL 既不等于 'user' 也不等于 'official'，
--    正向白名单 originType='user' 会把它一起排除在北极星分母之外。这正是 G-1 要防的
--    "失效在报表上看不出来"，而且方向更坏：真人卡被静默剔出分母。TC-CARD-17 断言的
--    「不写 originType 直接发布 → 报错」也依赖它。
--
-- ⚠️ 规格 §1.8.3b 把这一步注释掉了，理由是"存量数据回填完成后再置，分两步避免锁表期间写入失败"。
--    那个理由针对的是"表已有大量存量行"的场景；本表在本脚本里是新建表，首次安装无存量可回填。
--    故此处按存量情况分流：无 NULL 行则直接置 NOT NULL；有 NULL 行则跳过并告警，
--    由 DBA 按规格原文分两步走（先 UPDATE 回填，再单独置 NOT NULL）。
DO $$
DECLARE
    v_null_rows bigint;
BEGIN
    IF EXISTS (SELECT 1 FROM "information_schema"."columns"
                WHERE "table_name" = 't_memory_card' AND "column_name" = 'originType'
                  AND "is_nullable" = 'NO') THEN
        RETURN;  -- 已是 NOT NULL，幂等
    END IF;
    SELECT count(*) INTO v_null_rows FROM "t_memory_card" WHERE "originType" IS NULL;
    IF v_null_rows = 0 THEN
        ALTER TABLE "t_memory_card" ALTER COLUMN "originType" SET NOT NULL;
    ELSE
        RAISE WARNING 't_memory_card.originType 有 % 行为 NULL，暂不置 NOT NULL。'
                      '请按 SPEC-publish-and-ops §1.8.3b 分两步回填：'
                      'UPDATE "t_memory_card" SET "originType"=''user'' WHERE "originType" IS NULL; '
                      '然后 ALTER TABLE "t_memory_card" ALTER COLUMN "originType" SET NOT NULL; '
                      '🔴 在此之前北极星统计的前置闸门 G-1 未闭合，不得开启统计', v_null_rows;
    END IF;
END $$;

ALTER TABLE "t_memory_card" DROP CONSTRAINT IF EXISTS "t_memory_card_ck_origin_type";
ALTER TABLE "t_memory_card" ADD  CONSTRAINT "t_memory_card_ck_origin_type"
    CHECK ("originType" IN ('user','official'));

-- 支撑"按来源拆分"的全部口径：北极星分母、官方号零回应率、运营响应率
CREATE INDEX IF NOT EXISTS "t_memory_card_idx_origin_reviewed"
    ON "t_memory_card" ("originType", "reviewedAt") WHERE "reviewedAt" IS NOT NULL;

-- ------------------------- 回声类型字典（§1.8.2） ---------------------------
-- 回声类型会增加（R7「关注 ta」就是新增的一类）。写成 CHECK (type IN (...)) 则新增一类互动就要
-- 改约束、动 DDL；用字典表加一行数据即可。
-- countsToAcceptance 直接决定北极星分子，是"什么算被接住"的唯一真源——四处聚合（北极星 / 深共鸣率 /
-- 零回应卡占比 / 冷启动毕业判定）读同一列，而不是各写一遍 IN 白名单改漏一处没人发现。
-- 🔴 这一列能入表的前提是下面那道锁：没有锁，"配置项比代码好改"的顾虑完全成立，这列就不该存在。
CREATE TABLE IF NOT EXISTS "t_resonance_type" (
    "code"        varchar(16) NOT NULL,        -- R1..R7 / 后续新增（编号由产品分配）
    "slug"        varchar(32) NOT NULL,        -- 机器名，与埋点事件名对齐，如 follow_author
    "name"        varchar(32) NOT NULL,        -- 中文名，如「记得」
    "targetScope" varchar(8)  NOT NULL,        -- 该类型作用于 card | author | topic（与 t_resonance 同名）
    -- 🔴 这一列直接决定北极星分子。无默认值：新增类型必须显式表态，漏写则报错
    "countsToAcceptance" boolean NOT NULL,
    "attributable" boolean    NOT NULL DEFAULT false, -- 是否需要归因到某张卡
    "status"      varchar(8)  NOT NULL DEFAULT 'on',  -- on | off（下线不删行，历史数据还引用着）
    "sort"        integer     NOT NULL DEFAULT 0,
    "createdAt"   bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY ("code"),
    CONSTRAINT "t_resonance_type_uk_slug"  UNIQUE ("slug"),
    CONSTRAINT "t_resonance_type_ck_scope" CHECK ("targetScope" IN ('card','author','topic')),
    CONSTRAINT "t_resonance_type_ck_status" CHECK ("status" IN ('on','off'))
);

-- ---------------- 类型字典的变更审计流水（只追加，与审计账本同性质） -------------
CREATE TABLE IF NOT EXISTS "t_resonance_type_log" (
    "id"          bigint      NOT NULL,
    "code"        varchar(16) NOT NULL,
    "action"      varchar(8)  NOT NULL,        -- insert | update
    "beforeRow"   jsonb,                       -- 变更前整行（insert 时为 null）
    "afterRow"    jsonb       NOT NULL,
    "dbUser"      text        NOT NULL,        -- current_user，落库者身份
    "changedAt"   bigint      NOT NULL,
    PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "t_resonance_type_log_idx_code_time"
    ON "t_resonance_type_log" ("code", "changedAt");

-- 流水行的主键来源。规格原文用 clock_timestamp() 毫秒充当 id 并注明"实现侧用雪花 ID；此处示意"，
-- 但毫秒在同一毫秒内的两次变更会撞主键；触发器里拿不到应用层的 IDGenerator，故用序列。
CREATE SEQUENCE IF NOT EXISTS "t_resonance_type_log_id_seq" AS bigint START 1;

-- ---------------- 口径列禁改 + 禁删 + 全量留痕 -------------------------------
-- 要防的是：UPDATE t_resonance_type SET "countsToAcceptance"=true WHERE code='R7' —— 一条语句，
-- 北极星分子当天变宽，四个指标同时失真，没有 diff、没有 CR、没有人知道。
-- 为什么是"禁改"而不是"改了记账"：留痕能事后追责，但追责的前提是有人去看那张流水表，而指标注水
-- 恰好是那种"数字变好了、没人会去查为什么"的事故。所以口径列硬拒绝，措辞类列才走"允许改 + 留痕"。
CREATE OR REPLACE FUNCTION "t_resonance_type_guard"() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- 🔴 必须禁 DELETE：否则「删掉 R7 再以 countsToAcceptance=true 重新 INSERT」
        --    就是一条绕过下面禁改逻辑的完整路径。外键 RESTRICT 只挡"已被引用"的行，
        --    一个还没产生过数据的新类型是删得掉的。
        RAISE EXCEPTION 't_resonance_type 不允许删除行（code=%）：'
                        '类型下线请改 status=''off''，历史数据仍引用着这一行', OLD."code";
    END IF;

    IF TG_OP = 'UPDATE' THEN
        -- 身份列与口径列一律禁改（用 IS DISTINCT FROM，null 安全）
        IF NEW."countsToAcceptance" IS DISTINCT FROM OLD."countsToAcceptance" THEN
            RAISE EXCEPTION 't_resonance_type.countsToAcceptance 不可修改（% : % -> %）：'
                            '它直接决定北极星分子，改它等于改指标定义。'
                            '需要变更口径请新增一个类型并把旧类型 status 置 off，'
                            '让新旧口径在时间轴上可区分，而不是把历史一起改掉',
                            OLD."code", OLD."countsToAcceptance", NEW."countsToAcceptance";
        END IF;
        IF NEW."code" IS DISTINCT FROM OLD."code"
           OR NEW."slug" IS DISTINCT FROM OLD."slug"
           OR NEW."targetScope" IS DISTINCT FROM OLD."targetScope"
           OR NEW."attributable" IS DISTINCT FROM OLD."attributable" THEN
            RAISE EXCEPTION 't_resonance_type 的 code/slug/targetScope/attributable 均不可修改（code=%）：'
                            'slug 与埋点事件名绑定、targetScope 决定去重键，改它们会让历史数据对不上',
                            OLD."code";
        END IF;
        -- 允许改的只有 name（改中文措辞）、status（上下线）、sort（排序），且照样留痕
    END IF;

    INSERT INTO "t_resonance_type_log"
        ("id","code","action","beforeRow","afterRow","dbUser","changedAt")
    VALUES (
        nextval('t_resonance_type_log_id_seq'),
        NEW."code",
        lower(TG_OP),
        CASE WHEN TG_OP = 'UPDATE' THEN to_jsonb(OLD) ELSE NULL END,
        to_jsonb(NEW),
        current_user,
        (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS "trg_t_resonance_type_guard" ON "t_resonance_type";
CREATE TRIGGER "trg_t_resonance_type_guard"
    BEFORE INSERT OR UPDATE OR DELETE ON "t_resonance_type"
    FOR EACH ROW EXECUTE FUNCTION "t_resonance_type_guard"();

-- 种子数据 R1–R7。🔴 countsToAcceptance 必须逐行显式写出，不允许依赖默认值。
--
-- ⚠️ 用 WHERE NOT EXISTS 而不是只靠 ON CONFLICT DO NOTHING：BEFORE INSERT 触发器在冲突检测
--    之前就执行完了，所以 ON CONFLICT DO NOTHING 拦不住它——重跑本脚本时那 7 行虽然不会真的插进
--    字典表，却已经在审计流水里留下 7 行幻影 insert 记录，把"谁在什么时候动过口径"这件事冲淡。
--    NOT EXISTS 让已存在的行根本到不了触发器。ON CONFLICT 保留作并发兜底。
INSERT INTO "t_resonance_type"
    ("code","slug","name","targetScope","countsToAcceptance","attributable","sort","createdAt")
SELECT v."code", v."slug", v."name", v."targetScope",
       v."countsToAcceptance", v."attributable", v."sort", v."createdAt"
  FROM (VALUES
    ('R1','remember',      '记得',           'card',  true,  false, 1, 0::bigint),
    ('R2','footprint',     '留脚印',         'card',  true,  false, 2, 0),
    ('R3','leave_words',   '留一句话',       'card',  true,  false, 3, 0),
    ('R4','me_too',        '我也想起一件事', 'card',  true,  false, 4, 0),
    ('R5','shared_flower', '共同留一束心意', 'card',  true,  false, 5, 0),
    -- 🔴 R6/R7 是「持续关注」，不是「对某一条发布的表达」→ 不计入被接住
    ('R6','follow_topic',  '关注题材',       'topic', false, true,  6, 0),
    ('R7','follow_author', '关注 ta',        'author',false, true,  7, 0)
  ) AS v("code","slug","name","targetScope","countsToAcceptance","attributable","sort","createdAt")
 WHERE NOT EXISTS (SELECT 1 FROM "t_resonance_type" t WHERE t."code" = v."code")
ON CONFLICT ("code") DO NOTHING;

-- ---------------------------- 回声（共鸣表达 · §1.8.1） -----------------------
-- 一条「某人对某个对象做出的一次共鸣表达」，覆盖 R1 记得 / R2 留脚印 / R3 留一句话 /
-- R4 我也想起一件事 / R5 共同留一束心意 / R6 关注题材 / R7 关注 ta。
CREATE TABLE IF NOT EXISTS "t_resonance" (
    "id"                bigint      NOT NULL,               -- 雪花 ID
    "type"              varchar(16) NOT NULL,               -- R1..R7 / 后续新增类型，见 t_resonance_type
    "targetScope"       varchar(8)  NOT NULL,               -- card | author | topic
    -- ---- 对象（按 targetScope 三选一，由下方 CHECK 约束保证） ----
    "cardId"            bigint,                             -- scope=card：这条回声作用的那张卡
    "targetAuthorId"    bigint,                             -- scope=author：被关注的发布者
    "targetTopicId"     bigint,                             -- scope=topic：被关注的主题/窗口
    -- ---- 关注转化归因（🔴 只服务关注指标，不参与北极星，见下方红线；可空） ----
    "attributedCardId"  bigint,                             -- 触发该次关注的那张卡（"哪张卡带来了这次涨粉"）
    "attributionSource" varchar(24),                        -- 归因方式，如 entry_card / last_view / explicit
    "attributionVer"    varchar(16),                        -- 归因规则版本，便于口径变更后区分历史
    -- ---- 行为主体 ----
    "actorAccountId"    bigint      NOT NULL,
    "actorType"         varchar(8)  NOT NULL,               -- 🔴 无 DEFAULT：写入时必须显式声明（快照）
    -- ---- 载荷与审核 ----
    "payloadRef"        varchar(64),                        -- R3 文本 id / R4 相连卡 id / R5 心意笔数引用
    "moderationStatus"  varchar(16) NOT NULL DEFAULT 'passed',  -- passed | pending | rejected
    "abnormal"          boolean     NOT NULL DEFAULT false, -- 被判异常（刷量/恶意），口径同 S1′
    -- ---- 时间与软删 ----
    "createdAt"         bigint      NOT NULL DEFAULT 0,     -- UTC 毫秒
    "deletedAt"         bigint,
    "deletedBy"         bigint,
    "deleteReason"      varchar(64),
    PRIMARY KEY ("id"),

    -- 🔴 对象完整性：scope 决定哪一列必填，且另两列必须为空（防"既指卡又指作者"的脏数据）
    CONSTRAINT "t_resonance_ck_target" CHECK (
        ("targetScope" = 'card'   AND "cardId"         IS NOT NULL
                                  AND "targetAuthorId" IS NULL AND "targetTopicId" IS NULL)
     OR ("targetScope" = 'author' AND "targetAuthorId" IS NOT NULL
                                  AND "cardId"         IS NULL AND "targetTopicId" IS NULL)
     OR ("targetScope" = 'topic'  AND "targetTopicId"  IS NOT NULL
                                  AND "cardId"         IS NULL AND "targetAuthorId" IS NULL)
    ),
    -- actorType 白名单（三值封闭，新增身份类型需显式改约束——这是刻意的）
    CONSTRAINT "t_resonance_ck_actor_type" CHECK ("actorType" IN ('user','ops','system')),
    CONSTRAINT "t_resonance_ck_scope"      CHECK ("targetScope" IN ('card','author','topic')),
    -- 归因三列同生同灭：有归因卡就必须说清是怎么归的，否则口径无法追溯
    CONSTRAINT "t_resonance_ck_attribution" CHECK (
        ("attributedCardId" IS NULL     AND "attributionSource" IS NULL)
     OR ("attributedCardId" IS NOT NULL AND "attributionSource" IS NOT NULL)
    ),
    -- 🔴 外键一律 RESTRICT，禁止 CASCADE（CM-D1 禁止级联清理）
    CONSTRAINT "t_resonance_fk_card"    FOREIGN KEY ("cardId")
        REFERENCES "t_memory_card" ("id") ON DELETE RESTRICT,
    CONSTRAINT "t_resonance_fk_attr"    FOREIGN KEY ("attributedCardId")
        REFERENCES "t_memory_card" ("id") ON DELETE RESTRICT,
    CONSTRAINT "t_resonance_fk_actor"   FOREIGN KEY ("actorAccountId")
        REFERENCES "t_account" ("id")     ON DELETE RESTRICT,
    CONSTRAINT "t_resonance_fk_author"  FOREIGN KEY ("targetAuthorId")
        REFERENCES "t_account" ("id")     ON DELETE RESTRICT,
    CONSTRAINT "t_resonance_fk_topic"   FOREIGN KEY ("targetTopicId")
        REFERENCES "t_topic" ("id")       ON DELETE RESTRICT,
    CONSTRAINT "t_resonance_fk_type"    FOREIGN KEY ("type")
        REFERENCES "t_resonance_type" ("code") ON DELETE RESTRICT
);

-- 🔴 actorType 的两条硬约束（触发器，不只写在文字里）：
--   ① 必须快照而不是查询时 join t_account——否则某个员工离职后账号类型被改，历史北极星会被
--      追溯性改写，违反「历史数字不得被偷偷改写」。
--   ② 必须禁止 UPDATE——否则"把一行 ops 改成 user"就是一键刷北极星。
CREATE OR REPLACE FUNCTION "t_resonance_guard_actor_type"() RETURNS trigger AS $$
DECLARE
    v_account_type varchar(8);
BEGIN
    IF (TG_OP = 'INSERT') THEN
        SELECT "accountType" INTO v_account_type
          FROM "t_account" WHERE "id" = NEW."actorAccountId";
        IF v_account_type IS NULL THEN
            RAISE EXCEPTION 't_resonance: actorAccountId % 不存在，无法快照 actorType',
                            NEW."actorAccountId";
        END IF;
        IF NEW."actorType" <> v_account_type THEN
            RAISE EXCEPTION 't_resonance: actorType(%) 与账号当前 accountType(%) 不一致，'
                            '拒绝写入（必须是真实快照，不允许手填）',
                            NEW."actorType", v_account_type;
        END IF;
        RETURN NEW;
    END IF;

    IF (TG_OP = 'UPDATE') AND (NEW."actorType" IS DISTINCT FROM OLD."actorType") THEN
        RAISE EXCEPTION 't_resonance.actorType 不可变更（% -> %）：'
                        '改它等于篡改北极星，任何修正请软删后重写一行',
                        OLD."actorType", NEW."actorType";
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS "t_resonance_trg_actor_type" ON "t_resonance";
CREATE TRIGGER "t_resonance_trg_actor_type"
    BEFORE INSERT OR UPDATE ON "t_resonance"
    FOR EACH ROW EXECUTE FUNCTION "t_resonance_guard_actor_type"();

-- 去重唯一索引（按 scope 分三条 partial unique）
-- 为什么带 WHERE deletedAt IS NULL：取消关注 / 取消记得 = 软删（G0-1，不物理删）。带上这个条件，
-- 用户「取关后再关注」才能成功写入新行；不带则第二次会撞唯一键。
-- 🔴 随之而来的口径要求：一个人对同一对象取关又关注会留下多行，所以人数类指标一律按
--    COUNT(DISTINCT (对象, actorAccountId)) 去重，不能直接 COUNT(*)——否则反复取关关注就能刷数。
CREATE UNIQUE INDEX IF NOT EXISTS "t_resonance_uk_card_actor_type"
    ON "t_resonance" ("cardId", "actorAccountId", "type")
    WHERE "targetScope" = 'card' AND "deletedAt" IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS "t_resonance_uk_author_actor_type"
    ON "t_resonance" ("targetAuthorId", "actorAccountId", "type")
    WHERE "targetScope" = 'author' AND "deletedAt" IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS "t_resonance_uk_topic_actor_type"
    ON "t_resonance" ("targetTopicId", "actorAccountId", "type")
    WHERE "targetScope" = 'topic' AND "deletedAt" IS NULL;

-- 查询索引（对准 SPEC-admin-console §2.1.1 那两个 CTE）
-- ① 支撑 caught CTE：JOIN cardId + 过滤 actorType + createdAt 落在 7 天窗口内。
--    列序按「等值 → 等值 → 范围」排，让范围条件能用上索引。
--    partial 条件是刻意的：北极星只数「有效回声」（未软删、非异常、已过审），三个条件下沉进索引后
--    caught CTE 就是一次纯索引扫描。代价是被判异常的回声查起来慢——那是运营排查场景，可以慢。
CREATE INDEX IF NOT EXISTS "t_resonance_idx_card_actor_time"
    ON "t_resonance" ("cardId", "actorType", "createdAt")
    WHERE "deletedAt" IS NULL AND "abnormal" = false AND "moderationStatus" = 'passed';

-- ② 支撑按类型的分层统计（深共鸣率只看 R3/R4；兜底回应触发率只看 ops/system）
CREATE INDEX IF NOT EXISTS "t_resonance_idx_type_time"
    ON "t_resonance" ("type", "createdAt") WHERE "deletedAt" IS NULL;

-- ③ 支撑关注转化归因回溯：某张卡带来了多少次关注（只服务 §2.5 ④-b，不服务北极星）
CREATE INDEX IF NOT EXISTS "t_resonance_idx_attributed_card"
    ON "t_resonance" ("attributedCardId", "type") WHERE "attributedCardId" IS NOT NULL;

-- ⑥ 支撑关注关系的维度去重（净新增 / 取关率 / 30 日回访都按关系对聚合）
CREATE INDEX IF NOT EXISTS "t_resonance_idx_author_actor"
    ON "t_resonance" ("targetAuthorId", "actorAccountId", "createdAt")
    WHERE "targetScope" = 'author';

-- ④ 支撑用户维度查询（客服排障、我的互动列表）
CREATE INDEX IF NOT EXISTS "t_resonance_idx_actor_time"
    ON "t_resonance" ("actorAccountId", "createdAt");

-- ⑤ 支撑发布者维度（关注者列表、被接住的作者）
CREATE INDEX IF NOT EXISTS "t_resonance_idx_author_time"
    ON "t_resonance" ("targetAuthorId", "createdAt") WHERE "targetAuthorId" IS NOT NULL;

-- 🔴 归因三列的用途边界（防回流红线）：attributedCardId / attributionSource / attributionVer 的
--    用途是唯一的——回答「哪张卡带来了这次涨粉」，服务关注曝光转化率。
--    这三列不参与北极星「被接住的发布率」的任何计算。关注（R6/R7）不进 caught CTE。
--    专门写这条是因为：这三列长得很像一座能把关注接回北极星的桥，后人看到"关注已经能归因到
--    具体某张卡了"很容易顺手把它 JOIN 进分子。建这三列不是为了给北极星留后门。

-- ------------------------ 卡片可见性变更流水（§1.8.5 · R-17） -----------------
-- 北极星要判定「7 天窗口内作者是否撤回了可见性」。只看当前状态无法区分「窗口内就撤回了」（该整条
-- 剔除）和「窗口结束后才撤回」（不得影响已闭合的历史数字）。没有流水表只能用当前状态近似，结果是
-- 历史北极星被追溯性改写。
-- 同时记 status 迁移的用处：只记 visibility 分不出「作者撤回」与「运营下架」，而这两者的运营含义
-- 完全不同（一个是用户不想要了，一个是我们判它违规）。
-- 🔴 只追加、不修改、不删除（与审计账本同性质）。每一次可见性或状态迁移都要写一行，含系统自动迁移。
CREATE TABLE IF NOT EXISTS "t_card_visibility_log" (
    "id"             bigint      NOT NULL,
    "cardId"         bigint      NOT NULL,
    "fromVisibility" varchar(16) NOT NULL,      -- private | friends | public
    "toVisibility"   varchar(16) NOT NULL,
    "fromStatus"     varchar(16),               -- 同时记状态迁移，便于区分"作者撤回"与"运营下架"
    "toStatus"       varchar(16),
    "changedBy"      bigint      NOT NULL,
    "changedRole"    varchar(16) NOT NULL,      -- author | moderator | system
    "reasonCode"     varchar(32),
    "changedAt"      bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY ("id"),
    CONSTRAINT "t_card_visibility_log_ck_role"
        CHECK ("changedRole" IN ('author','moderator','system')),
    CONSTRAINT "t_card_visibility_log_fk_card" FOREIGN KEY ("cardId")
        REFERENCES "t_memory_card" ("id") ON DELETE RESTRICT,
    CONSTRAINT "t_card_visibility_log_fk_actor" FOREIGN KEY ("changedBy")
        REFERENCES "t_account" ("id")     ON DELETE RESTRICT
);

-- 支撑 eligible_cards 里的 NOT EXISTS 子查询（判"窗口内是否降级过"）
CREATE INDEX IF NOT EXISTS "t_card_visibility_log_idx_card_time"
    ON "t_card_visibility_log" ("cardId", "changedAt");

-- 支撑撤回率统计
CREATE INDEX IF NOT EXISTS "t_card_visibility_log_idx_time_to"
    ON "t_card_visibility_log" ("changedAt", "toVisibility");

-- ---------------------------- 审核工单（§2.6 + §2.6.1） -----------------------
-- ⚠️ 基表规格里只有 §2.6 的一行字段列表，没有正式 DDL；此处按该行落库，申诉五列照 §2.6.1 原文。
-- state 与卡片 status 是两个字段，不要合并：接口出参同时回显 state 与 cardStatus（§17.1）。
CREATE TABLE IF NOT EXISTS "t_moderation" (
    "id"            bigint      NOT NULL,
    "cardId"        bigint      NOT NULL,
    "submitBy"      bigint      NOT NULL DEFAULT 0,
    "autoRiskLevel" varchar(8)  NOT NULL DEFAULT 'low',   -- low | mid | high
    "autoSignals"   jsonb,
    "state"         varchar(16) NOT NULL DEFAULT 'pending',
    "handledBy"     bigint,
    "reasonCode"    varchar(32),
    "note"          text,
    "snapshot"      jsonb,                                 -- 处置时的卡面快照（§2.4 留痕四要素之一）
    "createdAt"     bigint      NOT NULL DEFAULT 0,
    "handledAt"     bigint,
    PRIMARY KEY ("id"),
    CONSTRAINT "t_moderation_ck_risk" CHECK ("autoRiskLevel" IN ('low','mid','high')),
    CONSTRAINT "t_moderation_ck_state" CHECK ("state" IN
        ('auto_pass','pending','blocked','approved','rejected','takendown','appealing')),
    CONSTRAINT "t_moderation_fk_card" FOREIGN KEY ("cardId")
        REFERENCES "t_memory_card" ("id") ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS "t_moderation_idx_state_created"
    ON "t_moderation" ("state", "createdAt");           -- 队列四 tab + 待处理优先
CREATE INDEX IF NOT EXISTS "t_moderation_idx_risk_created"
    ON "t_moderation" ("autoRiskLevel", "createdAt");    -- 高风险优先
CREATE INDEX IF NOT EXISTS "t_moderation_idx_card"
    ON "t_moderation" ("cardId");

-- §2.6.1 申诉五列：原字段表有 state='appealing' 但没有任何字段承载申诉本身。
-- 🔴 「一次」以 appealAt IS NOT NULL 为唯一判据，刻意不用计数列——计数列会诱导后人写
--    appealCount < 3 之类的放宽；一个时间戳只能从"空"变成"有值"，语义上就没有"再来一次"的位置。
ALTER TABLE "t_moderation" ADD COLUMN IF NOT EXISTS "appealText"      varchar(200);
ALTER TABLE "t_moderation" ADD COLUMN IF NOT EXISTS "appealAt"        bigint;
ALTER TABLE "t_moderation" ADD COLUMN IF NOT EXISTS "appealHandledBy" bigint;
ALTER TABLE "t_moderation" ADD COLUMN IF NOT EXISTS "appealHandledAt" bigint;
ALTER TABLE "t_moderation" ADD COLUMN IF NOT EXISTS "appealResult"    varchar(16);

ALTER TABLE "t_moderation" DROP CONSTRAINT IF EXISTS "t_moderation_ck_appeal_result";
ALTER TABLE "t_moderation" ADD  CONSTRAINT "t_moderation_ck_appeal_result"
    CHECK ("appealResult" IS NULL OR "appealResult" IN ('uphold','overturn'));

-- 🔴 appealAt 一经写入不可变更（与 reviewedAt 同性质）：允许改它等于允许把申诉机会退回给作者，
--    那就不是"一次"了。overturn 把卡送回 pending 也不重置它——撤销原处置不等于退还申诉机会。
CREATE OR REPLACE FUNCTION "t_moderation_guard_appeal_at"() RETURNS trigger AS $$
BEGIN
    IF OLD."appealAt" IS NOT NULL
       AND NEW."appealAt" IS DISTINCT FROM OLD."appealAt" THEN
        RAISE EXCEPTION 't_moderation.appealAt 首次写入后不可变更（% -> %）：'
                        '它是「一张卡一生只能申诉一次」的唯一判据，改它等于把申诉机会退回给作者',
                        OLD."appealAt", NEW."appealAt";
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS "t_moderation_trg_appeal_at" ON "t_moderation";
CREATE TRIGGER "t_moderation_trg_appeal_at"
    BEFORE UPDATE ON "t_moderation"
    FOR EACH ROW EXECUTE FUNCTION "t_moderation_guard_appeal_at"();

CREATE INDEX IF NOT EXISTS "t_moderation_idx_appeal_at"
    ON "t_moderation" ("appealAt") WHERE "appealAt" IS NOT NULL;  -- 申诉 tab + appealUsed 判据

-- ----------------------- 先审后发 / 先发后审开关（§17.1） --------------------
-- ⚠️ 规格未给表结构；按 PATCH /admin/moderation/settings 的入出参落一张单行配置表
--    （TC-MOD-05「后台可切、无需发版」依赖它）。
CREATE TABLE IF NOT EXISTS "t_moderation_setting" (
    "id"        integer      NOT NULL DEFAULT 1,        -- 单行表，恒为 1
    "mode"      varchar(16)  NOT NULL DEFAULT 'review_first',  -- review_first | publish_first
    "scopeJson" jsonb,                                  -- {riskLevel, authorTier}，可空 = 全量
    "updatedBy" bigint       NOT NULL DEFAULT 0,
    "updatedAt" bigint       NOT NULL DEFAULT 0,
    PRIMARY KEY ("id"),
    CONSTRAINT "t_moderation_setting_ck_id"   CHECK ("id" = 1),
    CONSTRAINT "t_moderation_setting_ck_mode" CHECK ("mode" IN ('review_first','publish_first'))
);
-- 默认 review_first（种子期先审后发，§2.2）
INSERT INTO "t_moderation_setting" ("id","mode") VALUES (1, 'review_first')
ON CONFLICT ("id") DO NOTHING;

-- ------------------------------- 举报（§17.1） -------------------------------
-- ⚠️ API-CONTRACT §17.6 明确「举报表结构 + 理由码字典 + C 端提交端点在任何现行规格里都没有定义，
--    本轮不代拟」。此处只按 GET /admin/reports 的出参字段落最小表，让该读取端点有数据源；
--    🔴 C 端提交端点与理由码字典仍缺，等产品决策，不在此代拟。
CREATE TABLE IF NOT EXISTS "t_report" (
    "id"         bigint      NOT NULL,
    "cardId"     bigint      NOT NULL,
    "reporterId" bigint      NOT NULL DEFAULT 0,
    "reasonCode" varchar(32) NOT NULL DEFAULT '',
    "note"       text,
    "status"     varchar(8)  NOT NULL DEFAULT 'open',   -- open | handled
    "createdAt"  bigint      NOT NULL DEFAULT 0,
    "handledAt"  bigint,
    PRIMARY KEY ("id"),
    CONSTRAINT "t_report_ck_status" CHECK ("status" IN ('open','handled')),
    CONSTRAINT "t_report_fk_card" FOREIGN KEY ("cardId")
        REFERENCES "t_memory_card" ("id") ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS "t_report_idx_status_created" ON "t_report" ("status", "createdAt");
CREATE INDEX IF NOT EXISTS "t_report_idx_card" ON "t_report" ("cardId");

-- --------------------------- 审计账本（API-CONTRACT §15.4） -------------------
-- ⚠️ 规格字段表把 ts 写成 timestamptz；此处按 SPEC-publish-and-ops §1.8「全库时间列一律 bigint
--    毫秒、不得两套时间类型并存」落为 bigint（并存会让聚合层每条 SQL 都要先判断时间列是什么类型）。
-- 保留期 ≥ 3 年；🔴 C 端零暴露——无任何 C 端路由读取本表。
CREATE TABLE IF NOT EXISTS "t_audit_log" (
    "id"         bigint      NOT NULL,
    "actor"      varchar(64) NOT NULL DEFAULT '',       -- 操作者 id（用户 / 后台运营 / 系统任务）
    "actorType"  varchar(16) NOT NULL DEFAULT 'user',   -- user | staff | system
    "action"     varchar(64) NOT NULL,
    "targetType" varchar(32) NOT NULL DEFAULT '',       -- pet | card | record | material | consent | config
    "targetId"   varchar(64) NOT NULL DEFAULT '',
    "scope"      jsonb,                                 -- 影响范围（审核处置记 §17.5 的五个键）
    "ts"         bigint      NOT NULL DEFAULT 0,        -- UTC 毫秒（规格记法为 timestamptz，见上）
    "ip"         varchar(64),
    "reviewerId" varchar(64),                           -- 双人复核的第二人（后台恢复必填，§15.5）
    PRIMARY KEY ("id"),
    CONSTRAINT "t_audit_log_ck_actor_type" CHECK ("actorType" IN ('user','staff','system'))
);
CREATE INDEX IF NOT EXISTS "t_audit_log_idx_target" ON "t_audit_log" ("targetType", "targetId", "ts");
CREATE INDEX IF NOT EXISTS "t_audit_log_idx_action_ts" ON "t_audit_log" ("action", "ts");
CREATE INDEX IF NOT EXISTS "t_audit_log_idx_actor_ts" ON "t_audit_log" ("actor", "ts");

-- ==================== 排序引擎内部状态：曝光记账（TECH-DESIGN §3.8） ==========
-- 定位：本表是"排序引擎的内部状态"，不是分析表。分析口径以 t_event（数仓，待建）为准。
--
-- 合规说明（供 CR 核对，避免被 G0-9/G0-10 误判打回）：
--   - 无 deletedAt：本表不是用户内容，是引擎派生状态，按保留期硬删除，故不适用
--     G0-9 的"唯一索引改部分索引"。
--   - 无 materialRef/consentRef：本表不是"素材派生物"（不由用户素材派生），是行为记账，
--     故不适用 G0-10 ① 的双外键要求。
--   - 但 (cardId, viewerId) 属 PIPL 下的行为个人信息：需保留期（35 天）+ 挂账号注销清理链路。
CREATE TABLE IF NOT EXISTS "t_card_exposure" (
    "id"        bigint      NOT NULL,                 -- 雪花
    "cardId"    bigint      NOT NULL DEFAULT 0,
    "viewerId"  bigint      NOT NULL DEFAULT 0,
    "day"       integer     NOT NULL DEFAULT 0,       -- yyyyMMdd，口径同 EchoApi.today()
    "viaBoost"  smallint    NOT NULL DEFAULT 0,       -- 1 = 本次曝光占用了冷启动保底位
    "channel"   varchar(16) NOT NULL DEFAULT '',      -- 召回通道枚举
    "pool"      varchar(16) NOT NULL DEFAULT '',      -- 池枚举
    "createdAt" bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
-- 🔴 这条唯一索引是整个曝光方案的正确性基石：24h 去重与幂等都靠它。内存去重集合只是性能优化，
--    它丢了、误判了、被 LRU 驱逐了都不影响最终计数正确性。
CREATE UNIQUE INDEX IF NOT EXISTS "t_card_exposure_uk_card_viewer_day"
    ON "t_card_exposure" ("cardId", "viewerId", "day");
CREATE INDEX IF NOT EXISTS "t_card_exposure_idx_card_day"
    ON "t_card_exposure" ("cardId", "day");           -- 额度 count
CREATE INDEX IF NOT EXISTS "t_card_exposure_idx_day"
    ON "t_card_exposure" ("day");                     -- 保留期清理 + 日对账

-- =============================================================================
-- S13 · C1「留一句话」（PALETTE §186 P0 清单 · DECISIONS §G⁗⁗⁗‴ S13）
-- =============================================================================
-- 核实结论：t_resonance 本身**不需要改**——R3 已在类型字典里（slug='leave_words'、
-- countsToAcceptance=true），payloadRef 也已预留给「R3 文本 id」。
-- 🔴 但 payloadRef 指向的那张表此前不存在，所以文本本身无处可落。本节补上它。

CREATE TABLE IF NOT EXISTS "t_resonance_text" (
    "id"          bigint      NOT NULL,               -- 雪花；即 t_resonance.payloadRef 指向的值
    "cardId"      bigint      NOT NULL,
    -- 卡作者（三选一处理权归他）。🔴 列名与 t_memory_card.ownerId 对齐，不另起 authorId
    "ownerId"     bigint      NOT NULL,
    "actorId"     bigint      NOT NULL,               -- 留话的人
    -- 🔴 60 字上限是 PALETTE §186 的产品约束，同时也是安全约束：短文本容不下引流话术与诈骗剧本。
    --    列宽按最坏情况给（一个汉字最多 4 字节），长度校验在应用层按「字符数」判，不是字节数。
    "body"        varchar(240) NOT NULL,
    -- 作者三选一：pending 待处理 / public 收下公开 / private 只自己看 / declined 不留
    -- 🔴 declined 不物理删：否则「作者拒了」与「从来没人留过」在数据上不可区分，
    --    而这两者对「深共鸣率分母该不该算这一次」是相反的答案。
    "disposition" varchar(8)  NOT NULL DEFAULT 'pending',
    "handledAt"   bigint,
    -- 文本安全闸的判定结果（passed | rejected）。🔴 rejected 的文本一律不进任何展示路径。
    "safetyState" varchar(8)  NOT NULL DEFAULT 'pending',
    "createdAt"   bigint      NOT NULL DEFAULT 0,
    "deletedAt"   bigint,
    PRIMARY KEY ("id")
);

ALTER TABLE "t_resonance_text" DROP CONSTRAINT IF EXISTS "t_resonance_text_ck_disposition";
ALTER TABLE "t_resonance_text" ADD  CONSTRAINT "t_resonance_text_ck_disposition"
    CHECK ("disposition" IN ('pending','public','private','declined'));

ALTER TABLE "t_resonance_text" DROP CONSTRAINT IF EXISTS "t_resonance_text_ck_safety";
ALTER TABLE "t_resonance_text" ADD  CONSTRAINT "t_resonance_text_ck_safety"
    CHECK ("safetyState" IN ('pending','passed','rejected'));

ALTER TABLE "t_resonance_text" DROP CONSTRAINT IF EXISTS "t_resonance_text_fk_card";
ALTER TABLE "t_resonance_text" ADD  CONSTRAINT "t_resonance_text_fk_card"
    FOREIGN KEY ("cardId") REFERENCES "t_memory_card" ("id") ON DELETE RESTRICT;

-- 作者处理队列：只看自己卡上待处理的
CREATE INDEX IF NOT EXISTS "t_resonance_text_idx_owner_pending"
    ON "t_resonance_text" ("ownerId", "createdAt")
    WHERE "disposition" = 'pending' AND "deletedAt" IS NULL;
-- 卡详情页取已公开的
CREATE INDEX IF NOT EXISTS "t_resonance_text_idx_card_public"
    ON "t_resonance_text" ("cardId", "createdAt")
    WHERE "disposition" = 'public' AND "safetyState" = 'passed' AND "deletedAt" IS NULL;
-- 🔴 一人对一张卡只留一句（无楼中楼、无对话；反骚扰的结构性保证，不靠前端 disable）
CREATE UNIQUE INDEX IF NOT EXISTS "t_resonance_text_uk_card_actor"
    ON "t_resonance_text" ("cardId", "actorId") WHERE "deletedAt" IS NULL;

-- ---------------------------- 功能开关（S13） --------------------------------
-- 🔴 这张表只存「开关现在是什么状态」，**不存「治理能力是否就绪」**。
--    就绪与否必须由代码在运行时探测真实能力（见 GovernanceCapability），不能落成一行可以被
--    UPDATE 的数据 —— 否则绕过前置条件的办法就从「改代码 + 评审」退化成「改一行数据」，
--    而那正是 S13 要防的东西（打开它不需要改代码、流水上看不出异常）。
CREATE TABLE IF NOT EXISTS "t_feature_switch" (
    "key"       varchar(64) NOT NULL,                 -- 如 leave_words
    "enabled"   boolean     NOT NULL DEFAULT false,   -- 🔴 P0 默认关闭
    "updatedBy" bigint      NOT NULL DEFAULT 0,
    "updatedAt" bigint      NOT NULL DEFAULT 0,
    -- 二次审批（SPEC-admin-console §4.7 ② 「内容可见性」类）：审批人必须与发起人不同
    "approvedBy" bigint,
    "approvedAt" bigint,
    PRIMARY KEY ("key")
);

-- 🔴 P0 默认关闭：这一行的 enabled=false 是裁定的一部分，不是缺省值凑巧如此。
INSERT INTO "t_feature_switch" ("key","enabled","updatedBy","updatedAt")
SELECT 'leave_words', false, 0, 0
 WHERE NOT EXISTS (SELECT 1 FROM "t_feature_switch" WHERE "key" = 'leave_words')
ON CONFLICT ("key") DO NOTHING;

-- =============================================================================
-- S3 治理能力（S13 开关的开启前置）
-- =============================================================================

-- ------------------------------- 拉黑（S8 / T7） ------------------------------
-- 🔴 单向。accountId 拉黑 peerId，peerId 不受任何影响、也不被告知。
--
-- 为什么没有 deletedAt（与本库多数表相反）：拉黑关系是 PIPL 下的行为个人信息，
-- 解除拉黑就该让这条关系真正消失。留软删记录等于「我曾经拉黑过谁」被长期保存，
-- 而这个信息对系统没有任何用处。解除动作本身在 t_audit_log 里有痕，够了。
CREATE TABLE IF NOT EXISTS "t_block" (
    "id"        bigint NOT NULL,
    "accountId" bigint NOT NULL,                    -- 拉黑发起方
    "peerId"    bigint NOT NULL,                    -- 被拉黑方（🔴 不可感知）
    "createdAt" bigint NOT NULL DEFAULT 0,
    PRIMARY KEY ("id"),
    CONSTRAINT "t_block_ck_not_self" CHECK ("accountId" <> "peerId"),
    CONSTRAINT "t_block_fk_account" FOREIGN KEY ("accountId")
        REFERENCES "t_account" ("id") ON DELETE RESTRICT,
    CONSTRAINT "t_block_fk_peer"    FOREIGN KEY ("peerId")
        REFERENCES "t_account" ("id") ON DELETE RESTRICT
);
-- 幂等：重复拉黑同一人不产生第二行
CREATE UNIQUE INDEX IF NOT EXISTS "t_block_uk_account_peer"
    ON "t_block" ("accountId", "peerId");
-- 🔴 这个方向的索引是拦截路径的热点：每次互动都要问「内容主人有没有拉黑我」，
--    即已知 peerId（互动发起人）反查 accountId。少了它，拉黑一上线就是全表扫。
CREATE INDEX IF NOT EXISTS "t_block_idx_peer" ON "t_block" ("peerId");

-- ------------------------------- 关注 ----------------------------------------
-- 单向订阅：accountId 关注 targetId。与 t_relation（亲友）不是一回事——
-- 亲友是双方都认的一层关系，关注是我单方面的「以后也想看见这个人发的」（E1b）。
--
-- 同样没有 deletedAt，理由与 t_block 一致：「我曾经关注过谁」对系统没有用处，
-- 而它是 PIPL 下的行为个人信息。取关就该让这条关系真正消失。
-- 🔴 拉黑连带解关注也走这里的物理删除，所以是不可逆的：解除拉黑不会把粉丝还回来。
CREATE TABLE IF NOT EXISTS "t_follow" (
    "id"        bigint NOT NULL,
    "accountId" bigint NOT NULL,                    -- 关注发起方
    "targetId"  bigint NOT NULL,                    -- 被关注方（作者）
    "createdAt" bigint NOT NULL DEFAULT 0,
    PRIMARY KEY ("id"),
    CONSTRAINT "t_follow_ck_not_self" CHECK ("accountId" <> "targetId"),
    CONSTRAINT "t_follow_fk_account" FOREIGN KEY ("accountId")
        REFERENCES "t_account" ("id") ON DELETE RESTRICT,
    CONSTRAINT "t_follow_fk_target"  FOREIGN KEY ("targetId")
        REFERENCES "t_account" ("id") ON DELETE RESTRICT
);
-- 幂等：重复关注同一人不产生第二行
CREATE UNIQUE INDEX IF NOT EXISTS "t_follow_uk_account_target"
    ON "t_follow" ("accountId", "targetId");
-- 粉丝数（E1b 公开且精确）按 targetId 数，作者主页每次打开都要数一遍。
-- 🔴 有这个索引不等于可以做「谁粉丝最多」——全站不做最受欢迎作者榜，
--    不要基于它写 ORDER BY count(*) DESC 的查询。
CREATE INDEX IF NOT EXISTS "t_follow_idx_target" ON "t_follow" ("targetId");

-- ------------------------------- 举报 ----------------------------------------
-- 决定：**复用 t_report，不另起新表**。理由见 ReportService 的类注释——
-- 举报是一个概念、多种对象，拆表会把审核台的一个队列拆成 union 三张表，
-- 而每加一类举报对象就要再改一次所有读路径。
--
-- 🔴 targetType='card' 时保留 cardId 并走外键，是为了不丢已有的引用完整性与索引；
--    其余类型 cardId 为 NULL（外键不适用——留言和账号不在 t_memory_card 里）。
ALTER TABLE "t_report" ADD COLUMN IF NOT EXISTS "targetType"   varchar(16) NOT NULL DEFAULT 'card';
ALTER TABLE "t_report" ADD COLUMN IF NOT EXISTS "targetId"     bigint      NOT NULL DEFAULT 0;
-- 举报转出的审核工单（工单流转：举报 → 工单 → 处置）
ALTER TABLE "t_report" ADD COLUMN IF NOT EXISTS "moderationId" bigint;
ALTER TABLE "t_report" ADD COLUMN IF NOT EXISTS "handledBy"    bigint;

-- 存量行回填 targetId（原表只有 cardId 一种对象）
UPDATE "t_report" SET "targetId" = "cardId" WHERE "targetId" = 0 AND "cardId" IS NOT NULL;

-- 账号 / 留言举报没有 cardId 可填，且 0 会撞外键 → 必须放开 NOT NULL
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 't_report' AND column_name = 'cardId'
                  AND is_nullable = 'NO') THEN
        ALTER TABLE "t_report" ALTER COLUMN "cardId" DROP NOT NULL;
    END IF;
END $$;

ALTER TABLE "t_report" DROP CONSTRAINT IF EXISTS "t_report_ck_target_type";
ALTER TABLE "t_report" ADD  CONSTRAINT "t_report_ck_target_type"
    CHECK ("targetType" IN ('card','text','account'));

-- 🔴 targetType 与 cardId 必须自洽：card 类举报的 cardId 要等于 targetId，
--    否则「按卡查举报」和「按 targetId 查举报」会给出两个不同的答案。
ALTER TABLE "t_report" DROP CONSTRAINT IF EXISTS "t_report_ck_target_consistency";
ALTER TABLE "t_report" ADD  CONSTRAINT "t_report_ck_target_consistency"
    CHECK (("targetType" =  'card' AND "cardId" IS NOT NULL AND "cardId" = "targetId")
        OR ("targetType" <> 'card' AND "cardId" IS NULL));

-- 🔴 防滥用去重：同一举报人对同一对象**只能有一条未处理的举报**。
--    刻意只约束 open：已处理过之后，若内容仍在且出现了新问题，用户应当还能再举报一次。
--    做成无条件唯一会把「这张卡我永远只能举报一次」写死，那是另一种错。
CREATE UNIQUE INDEX IF NOT EXISTS "t_report_uk_reporter_target_open"
    ON "t_report" ("reporterId", "targetType", "targetId") WHERE "status" = 'open';
CREATE INDEX IF NOT EXISTS "t_report_idx_target"
    ON "t_report" ("targetType", "targetId", "createdAt");

-- ---------------------- 关闭互动（t_memory_card.interaction） -------------------
-- 无需新列：interaction jsonb 已在。这里只把口径写死，避免各处各写一套键名。
--
-- 形状：{"remember":bool,"footprint":bool,"leaveWords":bool,"meToo":bool,"sharedFlower":bool}
-- 🔴 缺键 = 开启（默认全开）。不用 {"closed":[...]} 那种反向表示：反向表示下
--    「没有这个键」既可能是"没关"也可能是"这一类还不存在"，两者在读取时分不开。
--
-- 🔴 全局关 > 单条开：单条 interaction 只能**关**，不能反向打开一个被全局开关关掉的能力。
--    判定顺序写在 InteractionPolicy 里，不在这张表上——表只存作者意图。

-- ============================== 作品（t_work） ==============================
-- 规格真源：SPEC-works.md（2026-08-30 新建）。
--
-- 🔴 作品与回忆卡是**两个模型**，不要因为字段像就合并。区别在「谁让它存在」：
--   - 回忆卡（t_memory_card）是**私域产物**：AI 生成的近况、用户随手记、生命之书页，
--     它在用户没做任何事的情况下也会长出来，默认私密。
--   - 作品（t_work）是**公开物**：作者亲手挑了素材、写了字、按了发布。
--     它不会自己长出来，每一条都对应一次明确的作者意图。
--
-- 两者由 "sourceCardId" 连接：作者把一张回忆卡「发出去」，就长出一个作品，
-- 外键记住它的来路。用户自制上传的作品没有来路，该列为 NULL。
--
-- ⚠️ 建表顺序：t_account、t_memory_card 必须已存在（本表两个外键都指向它们）。

CREATE TABLE IF NOT EXISTS "t_work" (
    "id"           bigint       NOT NULL,
    "authorId"     bigint       NOT NULL DEFAULT 0,
    -- 来路：由哪张回忆卡发出。自制上传为 NULL——🔴 NULL 是合法值，不要补默认 0，
    -- 0 会被误当成"指向 id=0 的卡"，而外键在 0 上查不到行时报的错跟"没来路"是两回事。
    "sourceCardId" bigint,
    "mediaType"    varchar(16)  NOT NULL DEFAULT 'image',   -- image | video
    "mediaKey"     varchar(256) NOT NULL DEFAULT '',        -- POST /upload 返回的 resourceId
    -- 视频首帧。🔴 图片作品留空，不要拿 mediaKey 顶替：前端要靠这一列判断
    -- "该渲染 <img> 还是带 poster 的 <video>"，两列同值会让判断退化成猜 mediaType。
    "posterKey"    varchar(256) NOT NULL DEFAULT '',
    "durationMs"   integer      NOT NULL DEFAULT 0,         -- 视频时长；图片为 0
    -- 原始宽高，由客户端上传时读出后带上。🔴 存真实尺寸而不是 'tall'/'short' 档位：
    -- 瀑布流的高低错落是**渲染决定**，不同列数下同一张图该占的行高不一样，
    -- 档位在服务端定死等于把布局焊进数据，改版面就要洗数据。
    "width"        integer      NOT NULL DEFAULT 0,
    "height"       integer      NOT NULL DEFAULT 0,
    "title"        varchar(64)  NOT NULL DEFAULT '',        -- ≤30 字，应用层校验
    "body"         text,                                    -- ≤500 字，入库前过《温柔词表》
    "topicIds"     jsonb,                                   -- 主题标签 id 数组（0–3）
    "visibility"   varchar(16)  NOT NULL DEFAULT 'private', -- private | friends | public
    "status"       varchar(16)  NOT NULL DEFAULT 'draft',
    -- 与 t_memory_card.originType 同口径（G-1 前置闸门）：无默认值，漏写则报错。
    "originType"   varchar(16)  NOT NULL,                   -- user | official
    -- 🔴 AI 生成标识**独立成列，不从 sourceCardId join 推导**。三个理由：
    --   1. 自制上传路径下 sourceCardId 为 NULL，join 无从判断，而"AI 直接生成的作品"
    --      恰恰可能不经过中间那张卡；
    --   2. join 出来的事实会随被 join 行变化——来路卡被删了，作品就不是 AI 生成的了？
    --      那是**一句假话**，而这句假话是对监管说的；
    --   3. S-8 要求的显式标识要在列表页每一条上都渲染，列表查询不该为了它去 join。
    "aiGenerated"  boolean      NOT NULL DEFAULT false,
    "createdAt"    bigint       NOT NULL DEFAULT 0,
    "updatedAt"    bigint       NOT NULL DEFAULT 0,
    "publishedAt"  bigint,                                  -- 作者点发布的时刻
    "reviewedAt"   bigint,                                  -- 首次过审时刻，只写一次
    -- 软删三列（G0-1）。🔴 作品是用户内容，一律软删，禁止物理删。
    "deletedAt"    bigint,
    "deletedBy"    bigint,
    "deleteReason" varchar(64),
    PRIMARY KEY ("id"),
    CONSTRAINT "t_work_ck_media_type"
        CHECK ("mediaType" IN ('image','video')),
    CONSTRAINT "t_work_ck_visibility"
        CHECK ("visibility" IN ('private','friends','public')),
    CONSTRAINT "t_work_ck_status"
        CHECK ("status" IN ('draft','pending','public','rejected','takendown','appealing','deleted')),
    CONSTRAINT "t_work_ck_origin_type"
        CHECK ("originType" IN ('user','official')),
    -- 🔴 视频必须有首帧。没有首帧的视频在瀑布流里是一块黑，用户不知道点不点得下去；
    --    而这个缺失在单条预览时看不出来（<video> 会自己抽一帧），只在列表页暴露。
    CONSTRAINT "t_work_ck_video_poster"
        CHECK ("mediaType" <> 'video' OR "posterKey" <> ''),
    CONSTRAINT "t_work_fk_author" FOREIGN KEY ("authorId")
        REFERENCES "t_account" ("id") ON DELETE RESTRICT,
    CONSTRAINT "t_work_fk_source_card" FOREIGN KEY ("sourceCardId")
        REFERENCES "t_memory_card" ("id") ON DELETE RESTRICT
);

-- 作品瀑布：按发布时间倒序取公开作品。id 兜底保证顺序稳定，理由同 t_memory_card。
CREATE INDEX IF NOT EXISTS "t_work_idx_public_published"
    ON "t_work" ("publishedAt" DESC, "id" DESC)
    WHERE "status" = 'public' AND "deletedAt" IS NULL;

-- 个人作品页：某作者的全部作品（含未公开的，作者自己看得到）。
CREATE INDEX IF NOT EXISTS "t_work_idx_author_published"
    ON "t_work" ("authorId", "publishedAt" DESC, "id" DESC)
    WHERE "deletedAt" IS NULL;

-- 来路反查：这张回忆卡被发布成作品了吗（发布页要据此显示"已发布"而不是再给一个发布按钮）。
CREATE INDEX IF NOT EXISTS "t_work_idx_source_card"
    ON "t_work" ("sourceCardId") WHERE "sourceCardId" IS NOT NULL AND "deletedAt" IS NULL;

-- 🔴 一张回忆卡只能发出一个未删除的作品（S-7 局部唯一）。
--    带 deletedAt IS NULL：作者删掉作品之后应当还能重新发一次，无条件唯一会把
--    「这张卡我永远只能发一次」写死。
CREATE UNIQUE INDEX IF NOT EXISTS "t_work_uk_source_card"
    ON "t_work" ("sourceCardId")
    WHERE "sourceCardId" IS NOT NULL AND "deletedAt" IS NULL;

-- 按来源拆分口径（北极星分母、官方号观测），与 t_memory_card 的同名索引对齐。
CREATE INDEX IF NOT EXISTS "t_work_idx_origin_reviewed"
    ON "t_work" ("originType", "reviewedAt") WHERE "reviewedAt" IS NOT NULL;

-- ============================================================
-- 素材归属（t_resource）
-- ============================================================
-- 🔴 补的是一个「本来就该有、但一直没有」的东西：POST /upload 此前只把
-- accountId 打进日志就扔了，全库没有任何一张表知道某个 resourceId 是谁传的。
-- 后果是 POST /works 无从校验 mediaKey 归属——拿到别人的 key 就能把别人的
-- 照片发布成自己的作品（SPEC-security §4.14 E4）。
--
-- 它同时是下架的前置：IStorage 至今没有 delete 方法，软删只改数据库状态、
-- 字节永远在盘上（同上 E1）。要做到「下架即不可取」，得先知道有哪些 key。
CREATE TABLE IF NOT EXISTS "t_resource" (
    "resourceId"  varchar(64)  NOT NULL,
    "ownerId"     bigint       NOT NULL,
    "storageKey"  varchar(256) NOT NULL DEFAULT '',
    "contentType" varchar(128) NOT NULL DEFAULT '',
    "bytes"       bigint       NOT NULL DEFAULT 0,
    "createdAt"   bigint       NOT NULL DEFAULT 0,
    -- 吊销时刻。置上之后读取面应当拒绝，字节的物理清除另行排期。
    "revokedAt"   bigint,
    PRIMARY KEY ("resourceId"),
    CONSTRAINT "t_resource_fk_owner" FOREIGN KEY ("ownerId")
        REFERENCES "t_account" ("id") ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS "t_resource_idx_owner"
    ON "t_resource" ("ownerId", "createdAt" DESC);

-- 反查：从存储键找回归属，下架与审计都要走这条。
CREATE INDEX IF NOT EXISTS "t_resource_idx_key"
    ON "t_resource" ("storageKey");

CREATE TABLE IF NOT EXISTS "t_resource_cleanup_queue" (
    "resourceId" varchar(64) NOT NULL REFERENCES "t_resource"("resourceId") ON DELETE CASCADE,
    "reason" varchar(64) NOT NULL,
    "status" varchar(16) NOT NULL DEFAULT 'pending',
    "queuedAt" bigint NOT NULL,
    "completedAt" bigint,
    PRIMARY KEY ("resourceId")
);
CREATE INDEX IF NOT EXISTS "t_resource_cleanup_queue_idx_pending"
    ON "t_resource_cleanup_queue" ("status", "queuedAt");

-- ============================== 行为证据与适配（G-30 / G-34） ==============================
-- 四类对象分存。所谓“清除适配档案”只让在线结果退出生效链，不物理删除技术积累。

CREATE TABLE IF NOT EXISTS "t_behavior_event" (
    "eventId"              varchar(64)  NOT NULL,
    "accountId"            bigint       NOT NULL,
    "anonymousState"       varchar(16)  NOT NULL,
    "sessionId"            varchar(64)  NOT NULL,
    "eventName"            varchar(64)  NOT NULL,
    "surface"              varchar(64)  NOT NULL,
    "targetType"           varchar(64)  NOT NULL,
    "targetId"             varchar(128),
    "activeDurationMs"     bigint,
    "foregroundDurationMs" bigint,
    "loadWaitMs"           bigint,
    "attemptCount"         integer,
    "backtrackCount"       integer,
    "contextJson"          jsonb        NOT NULL DEFAULT '{}'::jsonb,
    "occurredAt"           bigint       NOT NULL,
    "receivedAt"           bigint       NOT NULL,
    "schemaVersion"        integer      NOT NULL,
    "purposeCode"          varchar(32)  NOT NULL,
    "idempotencyKey"       varchar(128) NOT NULL,
    "validityStatus"       varchar(16)  NOT NULL DEFAULT 'valid',
    "invalidReason"        varchar(64),
    PRIMARY KEY ("eventId"),
    CONSTRAINT "t_behavior_event_fk_account" FOREIGN KEY ("accountId")
        REFERENCES "t_account" ("id") ON DELETE RESTRICT,
    CONSTRAINT "t_behavior_event_uk_idempotency" UNIQUE ("accountId", "idempotencyKey"),
    CONSTRAINT "t_behavior_event_ck_anonymous" CHECK ("anonymousState" IN ('anonymous','bound')),
    CONSTRAINT "t_behavior_event_ck_purpose" CHECK ("purposeCode" IN ('ui_adaptation','public_recommendation','private_generation')),
    CONSTRAINT "t_behavior_event_ck_validity" CHECK ("validityStatus" IN ('valid','invalid')),
    CONSTRAINT "t_behavior_event_ck_durations" CHECK (
        ("activeDurationMs" IS NULL OR "activeDurationMs" >= 0) AND
        ("foregroundDurationMs" IS NULL OR "foregroundDurationMs" >= 0) AND
        ("loadWaitMs" IS NULL OR "loadWaitMs" >= 0)
    )
);

CREATE INDEX IF NOT EXISTS "t_behavior_event_idx_account_scope_time"
    ON "t_behavior_event" ("accountId", "purposeCode", "occurredAt" DESC);
CREATE INDEX IF NOT EXISTS "t_behavior_event_idx_processing"
    ON "t_behavior_event" ("receivedAt", "eventId") WHERE "validityStatus" = 'valid';

CREATE TABLE IF NOT EXISTS "t_explicit_feedback" (
    "feedbackId"       varchar(64)  NOT NULL,
    "accountId"        bigint       NOT NULL,
    "scope"            varchar(32)  NOT NULL,
    "targetType"       varchar(64)  NOT NULL,
    "targetId"         varchar(128) NOT NULL,
    "questionCode"     varchar(64)  NOT NULL,
    "answerCode"       varchar(64)  NOT NULL,
    "answerVersion"    integer      NOT NULL,
    "sourceSurface"    varchar(64)  NOT NULL,
    "occurredAt"       bigint       NOT NULL,
    "supersedesId"     varchar(64),
    "status"           varchar(16)  NOT NULL DEFAULT 'active',
    PRIMARY KEY ("feedbackId"),
    CONSTRAINT "t_explicit_feedback_fk_account" FOREIGN KEY ("accountId")
        REFERENCES "t_account" ("id") ON DELETE RESTRICT,
    CONSTRAINT "t_explicit_feedback_fk_supersedes" FOREIGN KEY ("supersedesId")
        REFERENCES "t_explicit_feedback" ("feedbackId") ON DELETE RESTRICT,
    CONSTRAINT "t_explicit_feedback_ck_scope" CHECK ("scope" IN ('ui_adaptation','public_recommendation','private_generation')),
    CONSTRAINT "t_explicit_feedback_ck_status" CHECK ("status" IN ('active','superseded','revoked'))
);

CREATE INDEX IF NOT EXISTS "t_explicit_feedback_idx_current"
    ON "t_explicit_feedback" ("accountId", "scope", "questionCode", "occurredAt" DESC)
    WHERE "status" = 'active';

CREATE TABLE IF NOT EXISTS "t_user_hypothesis" (
    "hypothesisId"     varchar(64)  NOT NULL,
    "accountId"        bigint       NOT NULL,
    "scope"            varchar(32)  NOT NULL,
    "dimension"        varchar(64)  NOT NULL,
    "valueCode"        varchar(64)  NOT NULL,
    "confidenceBand"   varchar(16)  NOT NULL,
    "evidenceCount"    integer      NOT NULL DEFAULT 0,
    "algorithmVersion" varchar(32)  NOT NULL,
    "validFrom"        bigint       NOT NULL,
    "validUntil"       bigint       NOT NULL,
    "status"           varchar(16)  NOT NULL DEFAULT 'active',
    "shadow"           boolean      NOT NULL DEFAULT true,
    "rejectedAt"       bigint,
    "clearedAt"        bigint,
    "clearBatchId"     varchar(64),
    "updatedAt"        bigint       NOT NULL,
    PRIMARY KEY ("hypothesisId"),
    CONSTRAINT "t_user_hypothesis_fk_account" FOREIGN KEY ("accountId")
        REFERENCES "t_account" ("id") ON DELETE RESTRICT,
    CONSTRAINT "t_user_hypothesis_ck_scope" CHECK ("scope" IN ('ui_adaptation','public_recommendation','private_generation')),
    CONSTRAINT "t_user_hypothesis_ck_confidence" CHECK ("confidenceBand" IN ('low','medium','high')),
    CONSTRAINT "t_user_hypothesis_ck_status" CHECK ("status" IN ('active','expired','rejected','cleared')),
    CONSTRAINT "t_user_hypothesis_ck_window" CHECK ("validUntil" > "validFrom")
);

CREATE INDEX IF NOT EXISTS "t_user_hypothesis_idx_active"
    ON "t_user_hypothesis" ("accountId", "scope", "dimension", "validUntil")
    WHERE "status" = 'active';

CREATE TABLE IF NOT EXISTS "t_hypothesis_event_evidence" (
    "hypothesisId" varchar(64) NOT NULL,
    "eventId"      varchar(64) NOT NULL,
    PRIMARY KEY ("hypothesisId", "eventId"),
    CONSTRAINT "t_hypothesis_event_fk_hypothesis" FOREIGN KEY ("hypothesisId")
        REFERENCES "t_user_hypothesis" ("hypothesisId") ON DELETE RESTRICT,
    CONSTRAINT "t_hypothesis_event_fk_event" FOREIGN KEY ("eventId")
        REFERENCES "t_behavior_event" ("eventId") ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS "t_hypothesis_feedback_evidence" (
    "hypothesisId" varchar(64) NOT NULL,
    "feedbackId"   varchar(64) NOT NULL,
    PRIMARY KEY ("hypothesisId", "feedbackId"),
    CONSTRAINT "t_hypothesis_feedback_fk_hypothesis" FOREIGN KEY ("hypothesisId")
        REFERENCES "t_user_hypothesis" ("hypothesisId") ON DELETE RESTRICT,
    CONSTRAINT "t_hypothesis_feedback_fk_feedback" FOREIGN KEY ("feedbackId")
        REFERENCES "t_explicit_feedback" ("feedbackId") ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS "t_adaptation_decision" (
    "decisionId"    varchar(64) NOT NULL,
    "accountId"     bigint      NOT NULL,
    "scope"         varchar(32) NOT NULL,
    "actionCode"    varchar(64) NOT NULL,
    "parametersJson" jsonb      NOT NULL DEFAULT '{}'::jsonb,
    "policyVersion" varchar(32) NOT NULL,
    "reasonCode"    varchar(64) NOT NULL,
    "reversible"    boolean     NOT NULL DEFAULT true,
    "shadow"        boolean     NOT NULL DEFAULT true,
    "status"        varchar(16) NOT NULL DEFAULT 'active',
    "appliedAt"     bigint,
    "expiresAt"     bigint,
    "revertedAt"    bigint,
    "revertReason"  varchar(64),
    "clearBatchId"  varchar(64),
    PRIMARY KEY ("decisionId"),
    CONSTRAINT "t_adaptation_decision_fk_account" FOREIGN KEY ("accountId")
        REFERENCES "t_account" ("id") ON DELETE RESTRICT,
    CONSTRAINT "t_adaptation_decision_ck_scope" CHECK ("scope" IN ('ui_adaptation','public_recommendation','private_generation')),
    CONSTRAINT "t_adaptation_decision_ck_status" CHECK ("status" IN ('active','expired','reverted','cleared'))
);

CREATE INDEX IF NOT EXISTS "t_adaptation_decision_idx_active"
    ON "t_adaptation_decision" ("accountId", "scope", "expiresAt")
    WHERE "status" = 'active';

CREATE TABLE IF NOT EXISTS "t_decision_hypothesis" (
    "decisionId"   varchar(64) NOT NULL,
    "hypothesisId" varchar(64) NOT NULL,
    PRIMARY KEY ("decisionId", "hypothesisId"),
    CONSTRAINT "t_decision_hypothesis_fk_decision" FOREIGN KEY ("decisionId")
        REFERENCES "t_adaptation_decision" ("decisionId") ON DELETE RESTRICT,
    CONSTRAINT "t_decision_hypothesis_fk_hypothesis" FOREIGN KEY ("hypothesisId")
        REFERENCES "t_user_hypothesis" ("hypothesisId") ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS "t_adaptation_preference" (
    "accountId" bigint      NOT NULL,
    "scope"     varchar(32) NOT NULL,
    "mode"      varchar(32) NOT NULL DEFAULT 'default',
    "version"   bigint      NOT NULL DEFAULT 0,
    "updatedAt" bigint      NOT NULL,
    PRIMARY KEY ("accountId", "scope"),
    CONSTRAINT "t_adaptation_preference_fk_account" FOREIGN KEY ("accountId")
        REFERENCES "t_account" ("id") ON DELETE RESTRICT,
    CONSTRAINT "t_adaptation_preference_ck_scope" CHECK ("scope" IN ('ui_adaptation','public_recommendation','private_generation')),
    CONSTRAINT "t_adaptation_preference_ck_mode" CHECK ("mode" IN ('default','personalized','non_personalized'))
);

CREATE TABLE IF NOT EXISTS "t_adaptation_profile_clear" (
    "clearId"                 varchar(64) NOT NULL,
    "accountId"               bigint      NOT NULL,
    "scope"                   varchar(32) NOT NULL,
    "clearedAt"               bigint      NOT NULL,
    "reason"                  varchar(64) NOT NULL DEFAULT 'user_requested',
    "affectedHypothesisCount" integer     NOT NULL DEFAULT 0,
    "affectedDecisionCount"   integer     NOT NULL DEFAULT 0,
    "policyVersion"           varchar(32) NOT NULL,
    PRIMARY KEY ("clearId"),
    CONSTRAINT "t_adaptation_clear_fk_account" FOREIGN KEY ("accountId")
        REFERENCES "t_account" ("id") ON DELETE RESTRICT,
    CONSTRAINT "t_adaptation_clear_ck_scope" CHECK ("scope" IN ('ui_adaptation','public_recommendation','private_generation'))
);

CREATE INDEX IF NOT EXISTS "t_adaptation_clear_idx_account_time"
    ON "t_adaptation_profile_clear" ("accountId", "clearedAt" DESC);

-- ----------------------- 私域单宠建档会话 -------------------------------
-- 主会话保存权威状态与完整聚合；五张附属表是可独立审计/演进的领域投影，
-- 与主会话版本在同一事务更新。禁止回落到进程内 Map。
CREATE TABLE IF NOT EXISTS "t_onboarding_session" (
    "onboardingId"  varchar(64) NOT NULL,
    "accountId"     bigint      NOT NULL,
    "status"        varchar(32) NOT NULL,
    "currentStep"   varchar(32) NOT NULL,
    "sessionVersion" bigint     NOT NULL DEFAULT 0,
    "payload"       text        NOT NULL,
    "createdAt"     bigint      NOT NULL,
    "updatedAt"     bigint      NOT NULL,
    PRIMARY KEY ("onboardingId")
);
CREATE INDEX IF NOT EXISTS "t_onboarding_session_idx_owner_updated"
    ON "t_onboarding_session" ("accountId", "updatedAt" DESC);
CREATE INDEX IF NOT EXISTS "t_onboarding_session_idx_status"
    ON "t_onboarding_session" ("status", "updatedAt");

CREATE TABLE IF NOT EXISTS "t_onboarding_subject" (
    "onboardingId" varchar(64) NOT NULL REFERENCES "t_onboarding_session"("onboardingId") ON DELETE CASCADE,
    "payload" text NOT NULL,
    "updatedAt" bigint NOT NULL,
    PRIMARY KEY ("onboardingId")
);
CREATE TABLE IF NOT EXISTS "t_onboarding_asset" (
    "onboardingId" varchar(64) NOT NULL REFERENCES "t_onboarding_session"("onboardingId") ON DELETE CASCADE,
    "payload" text NOT NULL,
    "updatedAt" bigint NOT NULL,
    PRIMARY KEY ("onboardingId")
);
CREATE TABLE IF NOT EXISTS "t_onboarding_answer" (
    "onboardingId" varchar(64) NOT NULL REFERENCES "t_onboarding_session"("onboardingId") ON DELETE CASCADE,
    "payload" text NOT NULL,
    "updatedAt" bigint NOT NULL,
    PRIMARY KEY ("onboardingId")
);
CREATE TABLE IF NOT EXISTS "t_pet_profile_fact" (
    "onboardingId" varchar(64) NOT NULL REFERENCES "t_onboarding_session"("onboardingId") ON DELETE CASCADE,
    "payload" text NOT NULL,
    "updatedAt" bigint NOT NULL,
    PRIMARY KEY ("onboardingId")
);
CREATE TABLE IF NOT EXISTS "t_generation_anchor" (
    "onboardingId" varchar(64) NOT NULL REFERENCES "t_onboarding_session"("onboardingId") ON DELETE CASCADE,
    "payload" text NOT NULL,
    "updatedAt" bigint NOT NULL,
    PRIMARY KEY ("onboardingId")
);
CREATE TABLE IF NOT EXISTS "t_onboarding_idempotency" (
    "onboardingId" varchar(64) NOT NULL REFERENCES "t_onboarding_session"("onboardingId") ON DELETE CASCADE,
    "idempotencyKey" varchar(128) NOT NULL,
    "requestHash" varchar(64) NOT NULL,
    "responseJson" text NOT NULL,
    "createdAt" bigint NOT NULL,
    PRIMARY KEY ("onboardingId", "idempotencyKey")
);
CREATE INDEX IF NOT EXISTS "t_onboarding_idempotency_idx_created"
    ON "t_onboarding_idempotency" ("createdAt");

-- 必须是整份脚本最后一条结构写入：前面任一步失败时绝不能提前宣告版本已完成。
INSERT INTO "t_schema_version" ("version", "appliedAt")
VALUES (2026083101, 1788177600000),
       (2026083102, 1788181200000),
       (2026090301, 1788418800000),
       (2026090601, 1788678000000),
       (2026090701, 1788764400000),
       (2026090702, 1788768000000)
ON CONFLICT ("version") DO NOTHING;
