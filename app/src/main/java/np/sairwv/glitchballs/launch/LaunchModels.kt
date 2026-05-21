package np.sairwv.glitchballs.launch

data class AppsFlyerLaunchData(
    val conversionData: Map<String, Any> = emptyMap(),
    val deepLinkData: Map<String, Any> = emptyMap(),
    val afId: String = "",
)

sealed interface ConfigDecision {
    data class Success(val url: String, val expiresAtMillis: Long) : ConfigDecision
    data class Rejected(val message: String) : ConfigDecision
    data class Failure(val reason: String) : ConfigDecision
}

sealed interface LaunchDecision {
    data object OpenGame : LaunchDecision
    data object ShowOfflineRetry : LaunchDecision
    data class OpenWeb(
        val url: String,
        val needsNotificationPrompt: Boolean,
        val isEphemeralUrl: Boolean,
    ) : LaunchDecision
}
