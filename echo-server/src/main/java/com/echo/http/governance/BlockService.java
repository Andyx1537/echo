package com.echo.http.governance;

import com.aengine.util.id.IDGenerator;
import com.echo.http.ApiException;
import lombok.extern.slf4j.Slf4j;

/**
 * 拉黑（{@code DECISIONS §G⁗‴ S8} / {@code SPEC-security §4.7} 补齐拉黑 {@code T7}）。
 *
 * <p>三条口径按裁定原文实现：<b>单向、彻底不可见、不可感知</b>。</p>
 *
 * <h2>🔴 静默失效：为什么被拉黑方看到的是"成功"</h2>
 *
 * <p>被拉黑方对拉黑方的内容做互动时，接口<b>返回成功</b>，但互动不生效、不落库、
 * 不出现在任何人的视图里。这不是偷懒，是刻意的：</p>
 *
 * <ul>
 *   <li>返回一个「你被拉黑了」的错误，等于<b>把一次拉黑变成一次冲突升级</b>——
 *       被拉黑方立刻知道是谁拉黑了他，接下来会换号、会去别处找对方。
 *       拉黑本该是让人安静下来的动作，不该是发起对抗的信号。</li>
 *   <li>返回一个别的错误（比如"操作失败"）更糟：它既没有隐藏拉黑，
 *       又让被拉黑方以为系统坏了，会反复重试。</li>
 * </ul>
 *
 * <p>⚠️ 这是<b>丧亲场景</b>下的产品判断。一般社交产品里"你已被对方拉黑"的提示是可接受的，
 * 这里不是。</p>
 *
 * <h2>拉黑与关注的关系</h2>
 *
 * <p>🔴 <b>本实现：拉黑自动解除双向关注，且不可逆。</b>理由与代价见
 * {@link #block} 的方法注释。</p>
 */
@Slf4j
public final class BlockService {

    private final BlockStore store;
    private final IDGenerator idGenerator;
    /** 解关注的连带动作；未装配时跳过（内存态联调）。 */
    private FollowUnlinker followUnlinker;

    /** 解除双向关注的回调。由关注模块提供实现。 */
    public interface FollowUnlinker {
        /** 解除 a→b 与 b→a 两个方向的关注，返回实际解除的条数。 */
        int unlinkBothDirections(long a, long b);
    }

    public BlockService(BlockStore store, IDGenerator idGenerator) {
        this.store = store;
        this.idGenerator = idGenerator;
    }

    public void setFollowUnlinker(FollowUnlinker followUnlinker) {
        this.followUnlinker = followUnlinker;
    }

    /**
     * 拉黑 {@code peerId}。幂等。
     *
     * <h3>🔴 连带解除双向关注 —— 建议与理由</h3>
     *
     * <p><b>做法</b>：拉黑时同时解除 {@code me→peer} 与 {@code peer→me} 两个方向的关注。</p>
     *
     * <p><b>理由</b>：关注（{@code R7}）是一条<b>持续的内容投递通道</b>——
     * {@code E1b} 的定义是「以后也想看见这个人发的」。若保留 {@code peer→me} 这个方向，
     * 被拉黑方的信息流里会继续出现拉黑方的新内容，那么「彻底不可见」就没有做到，
     * 拉黑只挡住了互动、没挡住围观。而围观正是骚扰的前一步。</p>
     *
     * <p><b>为什么连 {@code me→peer} 也一起解</b>：我拉黑了一个人，却还订阅着他的更新，
     * 这个状态本身没有合理解释，且会让我的信息流持续出现我刚刚决定不想看的人。</p>
     *
     * <p>⚠️ <b>代价，需要产品确认</b>：解关注<b>不可逆</b>。解除拉黑后粉丝关系不会自动恢复
     * （我们没有存"曾经关注过"，而按上文的理由也不该存）。所以「误拉黑再解除」会让作者
     * <b>永久少一个粉丝</b>，且粉丝数是展示在作者主页上的精确数字（{@code E1b}），
     * 作者能看出来少了。若产品认为这个代价不可接受，替代方案是「拉黑期间关注关系挂起
     * （不投递但保留），解除拉黑即恢复」——代价是要多一个状态列、且"挂起中的粉丝"
     * 算不算进粉丝数需要再定一次口径。</p>
     */
    public boolean block(long me, long peerId) {
        if (me == peerId) {
            throw new ApiException(ApiException.BAD_PARAM, "这个人是你自己呀。", "cannot block self");
        }
        boolean created = store.block(idGenerator.nextId(), me, peerId, System.currentTimeMillis());
        if (created && followUnlinker != null) {
            int removed = followUnlinker.unlinkBothDirections(me, peerId);
            log.info("拉黑连带解关注 me={} peer={} removed={}", me, peerId, removed);
        }
        // 🔴 绝不给 peerId 发任何通知（S8：拉黑后完全断流且对方无感知）
        return created;
    }

    /** 解除拉黑。幂等。🔴 同样不通知对方。 */
    public boolean unblock(long me, long peerId) {
        return store.unblock(me, peerId);
    }

    /**
     * {@code actor} 能否对 {@code ownerId} 的内容产生新的互动。
     *
     * <p>🔴 调用方在 false 时必须<b>返回成功但不生效</b>，不得抛错——见类注释。</p>
     */
    public boolean canInteract(long ownerId, long actor) {
        return !store.isBlocked(ownerId, actor);
    }

    /** 两人之间是否有任一方向的拉黑（可见性过滤用）。 */
    public boolean hidden(long a, long b) {
        return store.eitherDirection(a, b);
    }

    public BlockStore store() {
        return store;
    }
}
