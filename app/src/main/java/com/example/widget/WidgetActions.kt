package com.example.widget

/**
 * Intent actions sent by the home-screen quick-capture widget to
 * MainActivity. Each action maps to one widget tile and to the same
 * navigation the matching in-app button performs.
 */
object WidgetActions {
    /** Open the app with a new, empty idea in the editor. */
    const val NEW_IDEA = "com.example.widget.action.NEW_IDEA"

    /** Open the app with a new idea and start recording a melody at once. */
    const val RECORD_MELODY = "com.example.widget.action.RECORD_MELODY"

    /** Open the app with a new, empty song in the songwriting screen. */
    const val NEW_SONG = "com.example.widget.action.NEW_SONG"

    val ALL: Set<String> = setOf(NEW_IDEA, RECORD_MELODY, NEW_SONG)

    // Deep links, one per tile. Each URI mirrors the in-app navigation
    // route for the same destination (idea_editor/{ideaId}/{autoRecord},
    // songwriting/{songId}), under a lyricvault://widget/ prefix. The
    // widget puts one of these in the intent's data; the navigation layer
    // registers matching deep links on the two editor destinations and
    // MainActivity hands the intent to NavController.handleDeepLink. The
    // data URI also makes each tile's PendingIntent unambiguous: Android
    // tells PendingIntents apart by action AND data, never by extras.
    const val NEW_IDEA_URI = "lyricvault://widget/idea_editor/0/false"
    const val RECORD_MELODY_URI = "lyricvault://widget/idea_editor/0/true"
    const val NEW_SONG_URI = "lyricvault://widget/songwriting/0"

    val URIS: Set<String> = setOf(NEW_IDEA_URI, RECORD_MELODY_URI, NEW_SONG_URI)

    /** The deep-link URI for a tile action, or null for anything else. */
    fun uriFor(action: String?): String? = when (action) {
        NEW_IDEA -> NEW_IDEA_URI
        RECORD_MELODY -> RECORD_MELODY_URI
        NEW_SONG -> NEW_SONG_URI
        else -> null
    }
}
