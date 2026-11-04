package com.alef.assistentea.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.alef.assistentea.R
import com.alef.assistentea.service.AssistantService

/**
 * Widget da tela inicial: botão grande para falar com a A e botão para ligar/desligar a escuta contínua.
 * Tocar no widget é uma das formas que o Android aceita para ligar o microfone em segundo plano.
 */
class MicWidget : AppWidgetProvider() {

    companion object {
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, MicWidget::class.java))
            if (ids.isNotEmpty()) update(context, mgr, ids)
        }

        private fun update(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            val on = AssistantService.continuousOn
            val views = RemoteViews(context.packageName, R.layout.widget_mic).apply {
                setOnClickPendingIntent(
                    R.id.widgetMic, AssistantService.pending(context, AssistantService.ACTION_TAP, 31)
                )
                setOnClickPendingIntent(
                    R.id.widgetToggle,
                    AssistantService.pending(
                        context,
                        if (on) AssistantService.ACTION_STOP else AssistantService.ACTION_CONTINUOUS_ON,
                        32
                    )
                )
                setTextViewText(R.id.widgetToggle, if (on) "Ouvindo · parar" else "Ouvir sempre")
            }
            mgr.updateAppWidget(ids, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context, appWidgetManager, appWidgetIds)
    }
}
