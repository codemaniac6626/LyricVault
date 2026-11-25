package com.example.widget

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R

/**
 * The 4x2 home-screen quick-capture widget: a slim header row (app icon +
 * name) on top of three tiles in a row — "Catch a quick idea", "Record a
 * melody" and "Write a song". Tapping the header opens the app's home
 * screen via the plain launcher intent. Each tile fires a PendingIntent
 * that opens MainActivity with the matching [WidgetActions] action;
 * MainActivity and the navigation layer turn that into the same screen
 * the in-app buttons open.
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
        // The tile icons are vector drawables. A widget's layout is inflated
        // inside the launcher's process, where vector drawables can fail to
        // load and take the whole widget down with them (the widget renders
        // as an empty box). So the layout carries no icon resources; the
        // vectors are drawn to bitmaps here, in the app's own process, and
        // handed to the launcher as plain bitmaps.
        views.setImageViewBitmap(
            R.id.widget_icon_idea,
            vectorToBitmap(context, R.drawable.ic_widget_idea)
        )
        views.setImageViewBitmap(
            R.id.widget_icon_record,
            vectorToBitmap(context, R.drawable.ic_widget_mic)
        )
        views.setImageViewBitmap(
            R.id.widget_icon_song,
            vectorToBitmap(context, R.drawable.ic_widget_song)
        )
        // Same bitmap treatment for the header's app icon: the launcher
        // icon is an adaptive icon, and handing the launcher a plain
        // bitmap (rasterised here from the installed app's icon) avoids
        // the same in-launcher inflation risk as the tile vectors.
        views.setImageViewBitmap(
            R.id.widget_icon_app,
            appIconBitmap(context)
        )
        val openApp = openAppIntent(context)
        views.setOnClickPendingIntent(R.id.widget_header, openApp)
        views.setOnClickPendingIntent(R.id.widget_icon_app, openApp)
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

    private fun vectorToBitmap(context: Context, resId: Int): Bitmap =
        drawableToBitmap(context, context.getDrawable(resId)!!, ICON_SIZE_DP)

    private fun appIconBitmap(context: Context): Bitmap =
        drawableToBitmap(
            context,
            context.packageManager.getApplicationIcon(context.packageName),
            HEADER_ICON_SIZE_DP
        )

    private fun drawableToBitmap(
        context: Context,
        drawable: Drawable,
        sizeDp: Int
    ): Bitmap {
        val sizePx = (sizeDp * context.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun openAppIntent(context: Context): PendingIntent {
        // The plain launcher intent: opens the app on its home screen,
        // exactly like tapping the app icon in the launcher. (No deep
        // link, so the navigation layer stays on the start destination.)
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun tileIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        // ACTION_VIEW + the tile's deep-link URI: the navigation layer
        // resolves it with NavController.handleDeepLink, the standard
        // widget-to-screen path. (The earlier version reused the plain
        // launch intent with a custom action string, and taps landed on
        // the start destination instead of the tile's screen.)
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(WidgetActions.uriFor(action)),
            context,
            MainActivity::class.java
        ).apply {
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
        const val ICON_SIZE_DP = 30
        const val HEADER_ICON_SIZE_DP = 24
        const val REQUEST_OPEN_APP = 100
        const val REQUEST_NEW_IDEA = 101
        const val REQUEST_RECORD_MELODY = 102
        const val REQUEST_NEW_SONG = 103
    }
}
