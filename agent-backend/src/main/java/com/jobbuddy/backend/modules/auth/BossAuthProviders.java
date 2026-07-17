package com.jobbuddy.backend.modules.auth;

/** Boss 登录态在 auth_state 中使用的 provider 键。 */
public final class BossAuthProviders {
  public static final String STORAGE_PROVIDER = "jackwener/boss-cli";
  public static final String LEGACY_STORAGE_PROVIDER = "boss-zhipin";
  public static final String DISPLAY_PROVIDER = "boss-zhipin";

  private BossAuthProviders() {}
}
