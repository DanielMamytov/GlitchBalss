package np.sairwv.glitchballs.launch

import android.content.Context

class LaunchPreferences(context: Context) {

    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var mode: AppMode
        get() = AppMode.fromStorage(preferences.getString(KEY_MODE, AppMode.UNDECIDED.name))
        set(value) {
            preferences.edit().putString(KEY_MODE, value.name).apply()
        }

    var cachedWebUrl: String?
        get() = preferences.getString(KEY_CACHED_WEB_URL, null)
        set(value) {
            preferences.edit().putString(KEY_CACHED_WEB_URL, value).apply()
        }

    var cachedWebExpiresAtMillis: Long
        get() = preferences.getLong(KEY_CACHED_WEB_EXPIRES_AT, 0L)
        set(value) {
            preferences.edit().putLong(KEY_CACHED_WEB_EXPIRES_AT, value).apply()
        }

    var pushToken: String
        get() = preferences.getString(KEY_PUSH_TOKEN, "") ?: ""
        set(value) {
            preferences.edit().putString(KEY_PUSH_TOKEN, value).apply()
        }

    val notificationPermissionDenialCount: Int
        get() = preferences.getInt(KEY_NOTIFICATION_PERMISSION_DENIAL_COUNT, 0)

    val hasNotificationPermissionBeenDenied: Boolean
        get() = preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_DENIED, false)

    fun cacheWebTarget(url: String, expiresAtMillis: Long) {
        preferences.edit()
            .putString(KEY_CACHED_WEB_URL, url)
            .putLong(KEY_CACHED_WEB_EXPIRES_AT, expiresAtMillis)
            .apply()
    }

    fun isCachedWebTargetFresh(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val cachedUrl = cachedWebUrl
        val expiresAt = cachedWebExpiresAtMillis

        return !cachedUrl.isNullOrBlank() &&
                expiresAt > 0L &&
                nowMillis <= expiresAt
    }

    fun shouldShowNotificationPrompt(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val completed = preferences.getBoolean(KEY_NOTIFICATION_PROMPT_COMPLETED, false)
        val locked = isNotificationPermissionRequestLocked()
        val nextAllowedAt = preferences.getLong(KEY_NOTIFICATION_PROMPT_NEXT_ALLOWED_AT, 0L)

        return !completed && !locked && nowMillis >= nextAllowedAt
    }

    fun isNotificationPermissionRequestLocked(): Boolean {
        val explicitlyLocked =
            preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUEST_LOCKED, false)

        return explicitlyLocked ||
                notificationPermissionDenialCount >= MAX_SYSTEM_NOTIFICATION_DENIALS
    }

    fun markNotificationPromptCompleted() {
        preferences.edit()
            .putBoolean(KEY_NOTIFICATION_PROMPT_COMPLETED, true)
            .putBoolean(KEY_NOTIFICATION_PERMISSION_DENIED, false)
            .putBoolean(KEY_NOTIFICATION_PERMISSION_REQUEST_LOCKED, false)
            .putInt(KEY_NOTIFICATION_PERMISSION_DENIAL_COUNT, 0)
            .putLong(KEY_NOTIFICATION_PROMPT_NEXT_ALLOWED_AT, Long.MAX_VALUE)
            .apply()
    }

    fun markNotificationPermissionDenied(
        canRequestSystemDialogAgain: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val updatedDenialCount = notificationPermissionDenialCount + 1

        val shouldShowCustomPromptAgain =
            canRequestSystemDialogAgain &&
                    updatedDenialCount < MAX_SYSTEM_NOTIFICATION_DENIALS

        preferences.edit()
            .putBoolean(KEY_NOTIFICATION_PERMISSION_DENIED, true)
            .putInt(KEY_NOTIFICATION_PERMISSION_DENIAL_COUNT, updatedDenialCount)
            .putBoolean(KEY_NOTIFICATION_PROMPT_COMPLETED, !shouldShowCustomPromptAgain)
            .putBoolean(KEY_NOTIFICATION_PERMISSION_REQUEST_LOCKED, !shouldShowCustomPromptAgain)
            .putLong(
                KEY_NOTIFICATION_PROMPT_NEXT_ALLOWED_AT,
                if (shouldShowCustomPromptAgain) {
                    nowMillis + THREE_DAYS_MILLIS
                } else {
                    Long.MAX_VALUE
                },
            )
            .apply()
    }

    fun markNotificationPermissionUnavailable() {
        preferences.edit()
            .putBoolean(KEY_NOTIFICATION_PROMPT_COMPLETED, true)
            .putBoolean(KEY_NOTIFICATION_PERMISSION_DENIED, true)
            .putBoolean(KEY_NOTIFICATION_PERMISSION_REQUEST_LOCKED, true)
            .putInt(
                KEY_NOTIFICATION_PERMISSION_DENIAL_COUNT,
                maxOf(notificationPermissionDenialCount, MAX_SYSTEM_NOTIFICATION_DENIALS),
            )
            .putLong(KEY_NOTIFICATION_PROMPT_NEXT_ALLOWED_AT, Long.MAX_VALUE)
            .apply()
    }

    fun deferNotificationPrompt(nowMillis: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putBoolean(KEY_NOTIFICATION_PROMPT_COMPLETED, false)
            .putBoolean(KEY_NOTIFICATION_PERMISSION_REQUEST_LOCKED, false)
            .putLong(KEY_NOTIFICATION_PROMPT_NEXT_ALLOWED_AT, nowMillis + THREE_DAYS_MILLIS)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "glitchballs_launch_prefs"

        private const val KEY_MODE = "app_mode"
        private const val KEY_CACHED_WEB_URL = "cached_web_url"
        private const val KEY_CACHED_WEB_EXPIRES_AT = "cached_web_expires_at"
        private const val KEY_PUSH_TOKEN = "push_token"

        private const val KEY_NOTIFICATION_PROMPT_COMPLETED =
            "notification_prompt_completed"
        private const val KEY_NOTIFICATION_PERMISSION_DENIED =
            "notification_permission_denied"
        private const val KEY_NOTIFICATION_PERMISSION_DENIAL_COUNT =
            "notification_permission_denial_count"
        private const val KEY_NOTIFICATION_PERMISSION_REQUEST_LOCKED =
            "notification_permission_request_locked"
        private const val KEY_NOTIFICATION_PROMPT_NEXT_ALLOWED_AT =
            "notification_prompt_next_allowed_at"

        private const val MAX_SYSTEM_NOTIFICATION_DENIALS = 2
        private const val THREE_DAYS_MILLIS = 3L * 24L * 60L * 60L * 1000L
    }
}