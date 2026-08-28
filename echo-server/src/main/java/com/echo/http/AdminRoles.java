package com.echo.http;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code /admin/**} 的角色判定（{@code SPEC-publish-and-ops §2.5} 三角色）。
 *
 * <p>角色来源：环境变量 {@code ECHO_ADMIN_ROLES}，格式 {@code <accountId>:<role>,...}，
 * 如 {@code ECHO_ADMIN_ROLES=1001:supervisor,1002:reviewer,1003:readonly}。
 * <b>未列入者一律无后台权限</b>（返回 403），而不是默认放行。</p>
 *
 * <p>⚠️ 这是一条最小可用的独立鉴权链：账号身份仍沿用既有 Bearer token（网关已解出 accountId），
 * 本类只回答"这个账号在后台是什么角色"。完整的后台账号体系与二次审批
 * （{@code accountType} 变更需 super_admin 复核）不在本轮范围，见交付说明。</p>
 */
@Slf4j
public final class AdminRoles {

    /** 环境变量名。 */
    public static final String ENV_ADMIN_ROLES = "ECHO_ADMIN_ROLES";

    /** 审核员：看队列、通过/驳回/下架、写理由。 */
    public static final String REVIEWER = "reviewer";
    /** 审核主管：+ 处理申诉、配置先发/先审开关、看留痕报表。 */
    public static final String SUPERVISOR = "supervisor";
    /** 只读运营：看队列与报表，不可处置。 */
    public static final String READONLY = "readonly";

    private final Map<Long, String> roles;

    public AdminRoles(Map<Long, String> roles) {
        this.roles = Map.copyOf(roles);
    }

    /** 从 {@code ECHO_ADMIN_ROLES} 装配。未配置则是一张空表（后台整体不可用，符合"默认拒绝"）。 */
    public static AdminRoles fromEnv() {
        return parse(System.getenv(ENV_ADMIN_ROLES));
    }

    static AdminRoles parse(String raw) {
        Map<Long, String> parsed = new HashMap<>();
        if (raw != null && !raw.isBlank()) {
            for (String pair : raw.split(",")) {
                String entry = pair.trim();
                if (entry.isEmpty()) {
                    continue;
                }
                int colon = entry.lastIndexOf(':');
                if (colon <= 0) {
                    log.warn("{} 条目格式不对，已忽略: {}", ENV_ADMIN_ROLES, entry);
                    continue;
                }
                String role = entry.substring(colon + 1).trim().toLowerCase();
                if (!REVIEWER.equals(role) && !SUPERVISOR.equals(role) && !READONLY.equals(role)) {
                    log.warn("{} 未知角色，已忽略: {}", ENV_ADMIN_ROLES, entry);
                    continue;
                }
                try {
                    parsed.put(Long.parseLong(entry.substring(0, colon).trim()), role);
                } catch (NumberFormatException e) {
                    log.warn("{} accountId 不是数字，已忽略: {}", ENV_ADMIN_ROLES, entry);
                }
            }
        }
        if (parsed.isEmpty()) {
            log.info("未配置 {}，后台 /admin/** 全部拒绝访问（默认拒绝，不是默认放行）", ENV_ADMIN_ROLES);
        }
        return new AdminRoles(parsed);
    }

    /** @return 该账号的后台角色；无角色返回 null。 */
    public String roleOf(long accountId) {
        return roles.get(accountId);
    }

    /** 要求"能看"（三角色都可以）。 */
    public String requireRead(long accountId) {
        String role = roleOf(accountId);
        if (role == null) {
            throw forbidden(accountId, "any admin role");
        }
        return role;
    }

    /** 要求"能处置"（审核员及以上；只读运营一律 403）。 */
    public String requireHandle(long accountId) {
        String role = roleOf(accountId);
        if (!REVIEWER.equals(role) && !SUPERVISOR.equals(role)) {
            throw forbidden(accountId, "reviewer or supervisor");
        }
        return role;
    }

    /** 要求审核主管（🔴 处理申诉、切先审后发开关）。 */
    public String requireSupervisor(long accountId) {
        String role = roleOf(accountId);
        if (!SUPERVISOR.equals(role)) {
            throw forbidden(accountId, "supervisor");
        }
        return role;
    }

    private static ApiException forbidden(long accountId, String required) {
        // 后台可以回显技术细节（与 C 端相反，§17.1）
        return new ApiException(ApiException.RULE_FORBIDDEN,
                "这个操作需要更高的后台权限。",
                "admin role required: " + required + "; accountId=" + accountId);
    }
}
