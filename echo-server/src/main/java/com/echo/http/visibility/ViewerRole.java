package com.echo.http.visibility;

/**
 * 访客身份三档（{@code SPEC-interaction-flow §10.2}）。
 *
 * <p>🔴 <b>这里只有三档，不要加第四档。</b>「被拉黑方」不是一种身份——它在
 * {@link com.echo.http.visibility.VisibilityMatrix} 之前就被判掉了：任一方向拉黑 → 整页不可达
 * （判定顺序见 {@code RESEARCH-window-content-ownership §4.0}：①拉黑 → ②窗的可见性三档 →
 * ③才逐块裁剪）。把它混进这个枚举会让「不可达」和「可达但少几块」变成同一件事。</p>
 */
public enum ViewerRole {

    /** 该窗的主人本人。 */
    OWNER,

    /** 与主人建立了亲友关系的账号（判定见 {@code EchoApi.isFriend}，方向是「主人把他列为亲友」）。 */
    FRIEND,

    /** 其余任何人，含未登录游客。 */
    STRANGER
}
