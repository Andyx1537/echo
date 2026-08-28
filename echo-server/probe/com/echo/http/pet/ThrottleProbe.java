package com.echo.http.pet;

import com.echo.http.model.Models.PetEcho;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 回访近况节流：一天一条，跨零点换新的一条。 */
public final class ThrottleProbe {

    static int failures = 0;
    static final ZoneId Z = ZoneId.of("Asia/Shanghai");

    public static void main(String[] args) {
        System.out.println("\n---- 一天一条 ----");
        check("没有任何近况 → 该生成（回 null）",
                VisitEchoThrottle.todaysEcho(List.of(), at(2026, 8, 27, 9, 0), Z) == null);
        check("null 入参不炸", VisitEchoThrottle.todaysEcho(null, at(2026, 8, 27, 9, 0), Z) == null);

        long t9 = at(2026, 8, 27, 9, 0);
        List<PetEcho> one = new ArrayList<>(List.of(echo("e1", t9)));
        PetEcho found = VisitEchoThrottle.todaysEcho(one, at(2026, 8, 27, 21, 30), Z);
        check("🔴 同一天晚上再回访 → 复用早上那条（不生成第二条）",
                found != null && "e1".equals(found.echoId));

        System.out.println("\n---- 🔴 跨零点必须换新的一条 ----");
        // 23:59 产的那条，到了 00:01 就不是「今天」的了
        long lateNight = at(2026, 8, 27, 23, 59);
        List<PetEcho> late = new ArrayList<>(List.of(echo("e-late", lateNight)));
        check("同一天 23:59 那条，23:59 时算今天",
                VisitEchoThrottle.todaysEcho(late, lateNight, Z) != null);
        check("🔴 到了次日 00:01，昨天那条不算今天 → 该生成新的",
                VisitEchoThrottle.todaysEcho(late, at(2026, 8, 28, 0, 1), Z) == null);

        System.out.println("\n---- 历史脏数据（节流上线前一天多条）----");
        List<PetEcho> many = new ArrayList<>(List.of(
                echo("old-1", at(2026, 8, 27, 8, 0)),
                echo("old-3", at(2026, 8, 27, 20, 0)),
                echo("old-2", at(2026, 8, 27, 12, 0))));
        PetEcho newest = VisitEchoThrottle.todaysEcho(many, at(2026, 8, 27, 22, 0), Z);
        check("同一天有多条时取最新（顺序不敏感）",
                newest != null && "old-3".equals(newest.echoId));
        check("🔴 不做清理：历史那几条还在（节流只管住往后不再增长）", many.size() == 3);

        System.out.println("\n---- 天的口径与 EchoApi.today() 一致 ----");
        // EchoApi.today() = 年*10000 + 月*100 + 日
        check("dayOf 用 yyyyMMdd", VisitEchoThrottle.dayOf(at(2026, 8, 27, 12, 0), Z) == 20260827);
        check("跨月边界", VisitEchoThrottle.dayOf(at(2026, 8, 31, 23, 0), Z) == 20260831);
        check("跨年边界", VisitEchoThrottle.dayOf(at(2026, 12, 31, 23, 0), Z) == 20261231);

        System.out.println(failures == 0 ? "\n=== 全部通过 ===" : "\n=== 🔴 " + failures + " 条失败 ===");
        if (failures > 0) {
            System.exit(1);
        }
    }

    static long at(int y, int m, int d, int hh, int mm) {
        return LocalDate.of(y, m, d).atTime(LocalTime.of(hh, mm))
                .atZone(Z).toInstant().toEpochMilli();
    }

    static PetEcho echo(String id, long createdAt) {
        PetEcho e = new PetEcho();
        e.echoId = id;
        e.createdAt = createdAt;
        return e;
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   - " : "FAIL - ") + what);
        if (!ok) {
            failures++;
        }
    }
}
