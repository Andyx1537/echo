package com.echo.http.ranking;

/**
 * 分发权重 {@code W}（{@code TECH-DESIGN-feed-recall-and-exposure §8.1}）。
 *
 * <pre>
 * W(card) = W0 × γ^n × T_eff(a, n) × brakeFactor
 * </pre>
 *
 * <h2>🔴 这个模型的全部意义在于「只减不增」</h2>
 *
 * <p>{@code W0} 之外的三个因子<b>值域都是 (0, 1] 且单调不增</b>，所以 {@code W} 永远
 * {@code ≤ W0}，没有任何回升路径。互动量因此只能当<b>刹车</b>，不能当油门：
 * 没人喜欢就停推，而不是有人喜欢就加推。</p>
 *
 * <p>🔴 <b>不要给任何一个因子加上「乘一个大于 1 的数」的分支。</b>
 * 那不是给模型加一个特性，是把它变成另一个模型——马太效应正是这么开始的，
 * 而且第一步看起来总是很合理（「优质内容应该多推一点」）。</p>
 *
 * <h2>为什么是纯函数</h2>
 *
 * <p>{@code W} 是派生量，<b>不落库</b>（{@code §8.4.2}）。本类不持有状态、不读存储，
 * 输入全部由调用方给出，于是每个数字都能被单测逐个钉住——这套公式里没有一个常数
 * 是可以"差不多"的。</p>
 *
 * <h2>🔴 不在本类里的东西</h2>
 *
 * <p>情绪强度、悲伤浓度、催泪度——<b>任何名字、任何形态的情绪特征都不得进入本类</b>
 * （{@code SPEC-recommendation-ranking §9 红线四}、{@code §4.3 X1}）。互动计数、粉丝数、
 * 作者历史表现同样不得进入 {@code W0}（{@code §8.1.2}）。本类的输入参数清单本身就是这条红线的落点：
 * 它<b>接不了</b>那些量，因为没有对应的形参。</p>
 */
public final class WeightModel {

    /** 用户内容的初始权重（{@code §8.1.2}）。 */
    public static final double W0_USER = 1.0;
    /** 官方内容的初始权重——官方号天然有分发优势，用更低的 {@code W0} 抵掉（{@code §8.1.2}）。 */
    public static final double W0_OFFICIAL = 0.30;

    private final WeightConfig cfg;

    public WeightModel(WeightConfig cfg) {
        this.cfg = cfg;
    }

    public WeightConfig config() {
        return cfg;
    }

    /**
     * 时间衰减 {@code T(a)}，分段指数（{@code §8.1.4}）。
     *
     * <pre>
     * T(a) = exp(−a / 72)                        a ≤ 72h   （慢衰段）
     * T(a) = exp(−1) × exp(−(a − 72) / 36)       a > 72h   （快衰段）
     * </pre>
     *
     * <p>两段而不是一段：前三天是内容真正被看见的窗口，衰减要慢；过了这个窗口再留在流里
     * 挤占的是新内容的位置，所以后半段衰得快一倍。</p>
     *
     * @param ageHours 过审至今小时数 {@code a}；负数按 0 处理
     */
    public double timeDecay(double ageHours) {
        double a = Math.max(0.0, ageHours);
        if (a <= cfg.segmentHours()) {
            return Math.exp(-a / cfg.tau1Hours());
        }
        return Math.exp(-cfg.segmentHours() / cfg.tau1Hours())
                * Math.exp(-(a - cfg.segmentHours()) / cfg.tau2Hours());
    }

    /**
     * 带欠投地板的有效时间因子 {@code T_eff(a, n)}（{@code §8.4.3}）。
     *
     * <pre>
     * T_eff(a, n) = max( T(a), n &lt; D_min ? T_FLOOR : 0 )
     * </pre>
     *
     * <p>🔴 <b>地板不是配额复辟。</b>区别在于：配额是「保证你拿到 N 次曝光」，
     * 地板是「在你还没拿到同期队列都拿到过的那点曝光之前，不让时间把你压死」。
     * 前者是承诺一个总量，后者只是不让新内容因为发布得晚就直接输给时间。</p>
     *
     * @param n    累计主动分发去重曝光数
     * @param dMin 欠投判定线，见 {@link #dMin(int)}
     */
    public double effectiveTimeDecay(double ageHours, int n, int dMin) {
        double floor = n < dMin ? cfg.fairFloor() : 0.0;
        return Math.max(timeDecay(ageHours), floor);
    }

    /**
     * 欠投判定线 {@code D_min = clamp(同期队列 P25 曝光数, 20, 100)}（{@code §8.4.3}）。
     *
     * <p>用同期队列的 P25 而不是一个固定值：什么叫「投得够了」取决于当期整体的分发量，
     * 淡季的 20 次和旺季的 20 次不是一回事。钳位是为了让它在两个极端下都还讲得通。</p>
     */
    public int dMin(int peerQueueP25Exposures) {
        return Math.min(cfg.dMinHigh(), Math.max(cfg.dMinLow(), peerQueueP25Exposures));
    }

    /**
     * 退场线 {@code W_MIN = minRatio × W0}（{@code §8.3}）。{@code W < W_MIN} 即退出首页。
     *
     * <p>按 {@code W0} 的比例而不是一个绝对值：官方内容 {@code W0} 本来就低，
     * 用绝对值会让它一发出来就在退场线附近。</p>
     */
    public double minWeight(double w0) {
        return cfg.minRatio() * w0;
    }

    /**
     * 算 {@code W}。
     *
     * @param w0          初始权重，{@link #W0_USER} 或 {@link #W0_OFFICIAL}；发布时定死，永不改
     * @param n           累计主动分发去重曝光数
     * @param ageHours    过审至今小时数
     * @param dMin        欠投判定线
     * @param gamma       投放衰减因子。⚠️ 制作人已裁定 <b>{@code γ} 恒为 0.97、制动不再动它</b>，
     *                    所以常规路径应当用 {@link #weight(double, int, double, int, double)}
     *                    那个重载。本重载保留 {@code γ} 入口只为可测性与灰度，
     *                    🔴 <b>不要用它来实现任何"按情况换 γ"的机制</b>——那正是被推翻的形态
     * @param brakeFactor 负反馈制动累积系数，(0, 1]，初值 1.0
     */
    public double weight(double w0, int n, double ageHours, int dMin, double gamma, double brakeFactor) {
        requireBrake(brakeFactor);
        return w0 * Math.pow(gamma, Math.max(0, n))
                * effectiveTimeDecay(ageHours, n, dMin)
                * brakeFactor;
    }

    /** 用配置里的 {@code γ}（未被 {@code B1} 加重时的常规路径）。 */
    public double weight(double w0, int n, double ageHours, int dMin, double brakeFactor) {
        return weight(w0, n, ageHours, dMin, cfg.gamma(), brakeFactor);
    }

    /**
     * 从当前状态出发，这张卡<b>还能再被投多少次</b>才会跌破 {@code W_MIN} 退出首页。
     *
     * <p>这是 {@link BrakeMetrics} 的度量口径，也是回答「刹车到底管不管用」的那个数。
     * 龄期按<b>当前值冻结</b>——它是一个投影不是预测：真实世界里时间还会继续走，
     * 而时间衰减往往比投放衰减先把卡压下去（见 {@code docs/BRAKE-CALIBRATION.md}）。
     * 冻结龄期是为了把「刹车贡献了多少」单独拆出来看，不被时间的效果盖过。</p>
     *
     * @return 还能被投的次数；已经跌破退场线返回 0
     */
    public int remainingDeliveries(double w0, int n, double ageHours, int dMin, double brakeFactor) {
        requireBrake(brakeFactor);
        double floor = minWeight(w0);
        int extra = 0;
        // 上界纯粹是防呆：γ=0.97 时 n≈300 权重已到 1e-4 量级，正常配置下够不着
        while (extra < MAX_PROJECTED_DELIVERIES
                && weight(w0, n + extra, ageHours, dMin, brakeFactor) >= floor) {
            extra++;
        }
        return extra;
    }

    private static final int MAX_PROJECTED_DELIVERIES = 100_000;

    private static void requireBrake(double brakeFactor) {
        if (!(brakeFactor > 0.0 && brakeFactor <= 1.0)) {
            // 🔴 brakeFactor > 1 就是把刹车踩成了油门，模型的核心性质当场失效。
            //    这不该靠 code review 拦，值本身就不合法
            throw new IllegalArgumentException(
                    "brakeFactor 必须落在 (0,1]，制动只能让权重变小：" + brakeFactor);
        }
    }
}
