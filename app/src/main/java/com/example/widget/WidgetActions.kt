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
}
