package com.echo.http.work;

/**
 * 作品领域模型，与 {@code schema.sql} 的 {@code t_work} 字段一一对应。
 *
 * <h2>🔴 作品不是回忆卡，两个模型不要合并</h2>
 *
 * <p>字段看着像，但「谁让它存在」是相反的：</p>
 *
 * <ul>
 *   <li><b>回忆卡</b>（{@code t_memory_card}）是<b>私域产物</b>。AI 生成的近况、用户随手记、
 *       生命之书页——用户什么都不做它也会长出来，所以默认私密，所以它的产品问题是
 *       「要不要给别人看」。</li>
 *   <li><b>作品</b>（本类）是<b>公开物</b>。作者挑了素材、写了字、按了发布，
 *       每一条都对应一次明确的作者意图，所以它的产品问题是「发得好不好」。</li>
 * </ul>
 *
 * <p>两者由 {@link #sourceCardId} 连接：作者把一张回忆卡「发出去」就长出一个作品，
 * 外键记住来路。用户自制上传的作品没有来路，该字段为 {@code null}。</p>
 *
 * <p>沿 {@code ModerationModels} 的写法：字段 public，由 store 直接读写、由 api 组装成契约 JSON。</p>
 */
public final class Work {

    /** 媒体类型。 */
    public static final class MediaType {
        public static final String IMAGE = "image";
        public static final String VIDEO = "video";

        private MediaType() {
        }
    }

    /**
     * 作品状态。
     *
     * <p>🔴 比回忆卡少了 {@code active} 与 {@code blocked} 两态，是刻意的：
     * {@code active} 在卡那边表示「已存在但未公开」，而作品不存在这种中间态——
     * 没发布的作品就是 {@code draft}。{@code blocked} 是机审直接拦下，
     * 作品走的是先 {@code pending} 后人工，没有机审直拦这条路。</p>
     */
    public static final class Status {
        public static final String DRAFT = "draft";
        public static final String PENDING = "pending";
        public static final String PUBLIC = "public";
        public static final String REJECTED = "rejected";
        public static final String TAKENDOWN = "takendown";
        public static final String APPEALING = "appealing";
        public static final String DELETED = "deleted";

        private Status() {
        }
    }

    /** 内容来源，与 {@code t_memory_card.originType} 同口径（北极星前置闸门 G-1）。 */
    public static final class OriginType {
        public static final String USER = "user";
        public static final String OFFICIAL = "official";

        private OriginType() {
        }
    }

    public long id;
    public long authorId;

    /**
     * 来路：由哪张回忆卡发出。自制上传为 {@code null}。
     *
     * <p>🔴 {@code null} 是合法值，不要用 0 代替。0 会被读成「指向 id=0 的卡」，
     * 而那张卡不存在时报的错，跟「这个作品本来就没有来路」是两回事。</p>
     */
    public Long sourceCardId;

    public String mediaType = MediaType.IMAGE;
    /** {@code POST /upload} 返回的 resourceId。 */
    public String mediaKey = "";
    /**
     * 视频首帧。图片作品留空。
     *
     * <p>🔴 不要拿 {@link #mediaKey} 顶替：前端靠这一列判断该渲染 {@code <img>}
     * 还是带 poster 的 {@code <video>}，两列同值会让判断退化成猜 {@link #mediaType}。</p>
     */
    public String posterKey = "";
    public int durationMs;
    /**
     * 原始宽高，客户端上传时读出后带上。
     *
     * <p>🔴 存真实尺寸而不是 {@code tall}/{@code short} 档位：瀑布流的高低错落是
     * <b>渲染决定</b>，不同列数下同一张图该占的行高不一样。档位在服务端定死
     * 等于把布局焊进数据，改版面就要洗数据。</p>
     */
    public int width;
    public int height;

    public String title = "";
    public String body = "";
    /** 主题标签 id 数组的 json 文本（0–3 个）。 */
    public String topicIdsJson;

    public String visibility = "private";
    public String status = Status.DRAFT;
    public String originType = OriginType.USER;

    /**
     * AI 生成标识。
     *
     * <p>🔴 <b>独立成列，不从 {@link #sourceCardId} join 推导。</b>
     * 自制上传路径下来路为空，join 无从判断；而 join 出来的事实会随被 join 行变化——
     * 来路卡被删了，作品就不是 AI 生成的了？那是一句假话，且这句假话是对监管说的。
     * 显式标识（S-8）要在列表页每一条上渲染，列表查询也不该为它去 join。</p>
     */
    public boolean aiGenerated;

    public long createdAt;
    public long updatedAt;
    /** 作者点发布的时刻。未发布为 {@code null}。 */
    public Long publishedAt;
    /** 首次过审时刻，只写一次。 */
    public Long reviewedAt;

    public Long deletedAt;
    public Long deletedBy;
    public String deleteReason;

    public boolean isVideo() {
        return MediaType.VIDEO.equals(mediaType);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
