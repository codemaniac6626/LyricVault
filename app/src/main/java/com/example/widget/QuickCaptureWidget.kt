package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R

/**
 * The 4x2 home-screen quick-capture widget: one wide widget holding three
 * tiles in a row — "Catch a quick idea", "Record a melody" and "Write a
 * song". Each tile fires a PendingIntent that opens MainActivity with the
 * matching [WidgetActions] action; MainActivity and the navigation layer
 * turn that into the same screen the in-app buttons open.
 */
class QuickCaptureWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context))
        }
    }

    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_capture)
        views.setOnClickPendingIntent(
            R.id.widget_tile_idea,
            tileIntent(context, WidgetActions.NEW_IDEA, REQUEST_NEW_IDEA)
        )
        views.setOnClickPendingIntent(
            R.id.widget_tile_record,
            tileIntent(context, WidgetActions.RECORD_MELODY, REQUEST_RECORD_MELODY)
        )
        views.setOnClickPendingIntent(
            R.id.widget_tile_song,
            tileIntent(context, WidgetActions.NEW_SONG, REQUEST_NEW_SONG)
        )
        return views
    }

    private fun tileIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val REQUEST_NEW_IDEA = 101
        const val REQUEST_RECORD_MELODY = 102
        const val REQUEST_NEW_SONG = 103
    }
}
