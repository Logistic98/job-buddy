package com.jobbuddy.backend.modules.auth.event;

/**
 * Boss 登录态失效事件。
 *
 * Boss 工具返回 4001（未登录/登录态不完整）时由底层能力层发布，用于通知上层登录态缓存
 * 立即失效。采用事件而非直接依赖，是为了避免与 BossAuthService 形成循环依赖
 * （BossAuthService 已依赖 BossCliService）。
 */
public class BossAuthLostEvent {
    private final String source;

    public BossAuthLostEvent(String source) {
        this.source = source == null ? "boss_auth_lost" : source;
    }

    public String getSource() {
        return source;
    }
}
