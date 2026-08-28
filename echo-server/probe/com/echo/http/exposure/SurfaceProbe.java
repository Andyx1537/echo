package com.echo.http.exposure;

import com.aengine.util.id.IDGenerator;

import java.util.List;
import java.util.Set;

/** 下发面：n 只在全屏层记。 */
public final class SurfaceProbe {

    static int failures = 0;
    static final long VIEWER = 5L;

    public static void main(String[] args) {
        ExposureConfig cfg = new ExposureConfig(true, 1800, 1000, 200, 50, 1000, 100, 50);
        FeedRequestRegistry reg = new FeedRequestRegistry(cfg);
        ExposureRecorder rec = new ExposureRecorder(cfg, reg, null, new IDGenerator(1L));

        String grid = reg.register(VIEWER, FeedRequestRegistry.KIND_CARD,
                FeedRequestRegistry.SURFACE_GRID, List.of("1001", "1002"), Set.of(), "", "");
        ExposureRecorder.Outcome g = rec.record(grid, VIEWER, items());
        check("🔴 网格层：accepted 必须为 0", g.accepted() == 0);
        check("网格层：整批算作 rejected", g.rejected() == 2);
        check("🔴 网格层：一条都没进待写队列", rec.pendingCount() == 0);

        String imm = reg.register(VIEWER, FeedRequestRegistry.KIND_CARD,
                FeedRequestRegistry.SURFACE_IMMERSIVE, List.of("1001", "1002"), Set.of(), "", "");
        ExposureRecorder.Outcome i = rec.record(imm, VIEWER, items());
        check("🔴 全屏层：accepted 必须 > 0（不能只断 rejected==0）", i.accepted() == 2);
        check("全屏层：无拒收", i.rejected() == 0);

        String win = reg.register(VIEWER, FeedRequestRegistry.KIND_WINDOW,
                FeedRequestRegistry.SURFACE_IMMERSIVE, List.of("1001"), Set.of(), "", "");
        check("窗口口径即使在全屏层也拒收（两个判据正交）",
                rec.record(win, VIEWER, items()).accepted() == 0);

        check("快照自判：卡+网格 不记", !reg.lookup(grid).countsTowardExposure());
        check("快照自判：卡+全屏 记", reg.lookup(imm).countsTowardExposure());
        check("快照自判：窗+全屏 不记", !reg.lookup(win).countsTowardExposure());

        System.out.println(failures == 0 ? "\n=== 全部通过 ===" : "\n=== 🔴 " + failures + " 条失败 ===");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** ts 必须晚于快照登记时刻，所以每次现造。 */
    static List<ExposureRecorder.Item> items() {
        long now = System.currentTimeMillis();
        return List.of(new ExposureRecorder.Item("1001", 0, 1500, now),
                new ExposureRecorder.Item("1002", 1, 1500, now));
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   - " : "FAIL - ") + what);
        if (!ok) {
            failures++;
        }
    }
}
