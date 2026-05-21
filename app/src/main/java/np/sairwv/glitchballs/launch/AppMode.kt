package np.sairwv.glitchballs.launch

enum class AppMode {
    UNDECIDED,
    WEBVIEW,
    GAME,
    ;

    companion object {
        fun fromStorage(value: String?): AppMode {
            return entries.firstOrNull { it.name == value } ?: UNDECIDED
        }
    }
}
