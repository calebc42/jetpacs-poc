// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.calebc42.jetpacs.core.database.WidgetBindingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WidgetConfigurationActivity : ComponentActivity() {
    private val widgetId: Int by lazy {
        intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        if (intent?.action != AppWidgetManager.ACTION_APPWIDGET_CONFIGURE ||
            !isOurWidget(widgetId)
        ) {
            finish()
            return
        }
        setContent {
            var surfaceId by rememberSaveable { mutableStateOf("widget:main") }
            var suggestions by remember { mutableStateOf(emptyList<String>()) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(widgetId) {
                val app = application as JetpacsApplication
                val loaded = withContext(Dispatchers.IO) {
                    val existing = app.container.database.widgetDao().getBinding(widgetId)
                    val cached = app.container.database.surfaceDao()
                        .getRecords(app.container.pairingId.value)
                        .filter { it.present && it.surfaceId.startsWith("widget:") }
                        .map { it.surfaceId }
                        .distinct()
                        .sorted()
                    existing?.surfaceId to cached
                }
                loaded.first?.let { surfaceId = it }
                suggestions = loaded.second
            }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Configure Jetpacs widget", style = MaterialTheme.typography.headlineSmall)
                        Text("Bind this Android instance to any Emacs widget:<name> surface.")
                        OutlinedTextField(
                            value = surfaceId,
                            onValueChange = { surfaceId = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Surface") },
                            singleLine = true,
                            isError = error != null,
                            supportingText = error?.let { message -> { Text(message) } },
                        )
                        if (suggestions.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(suggestions) { suggestion ->
                                    OutlinedButton(onClick = { surfaceId = suggestion }) {
                                        Text(suggestion.substringAfter("widget:"))
                                    }
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (!WIDGET_SURFACE.matches(surfaceId) ||
                                        surfaceId.toByteArray(Charsets.UTF_8).size > 128
                                    ) {
                                        error = "Use widget:<name> with letters, numbers, ., _, :, /, or -."
                                    } else {
                                        save(surfaceId)
                                    }
                                },
                            ) { Text("Save") }
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = ::finish,
                            ) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }

    private fun save(surfaceId: String) {
        lifecycleScope.launch {
            val app = application as JetpacsApplication
            withContext(Dispatchers.IO) {
                val dao = app.container.database.widgetDao()
                val prior = dao.getBinding(widgetId)
                val now = System.currentTimeMillis().coerceAtLeast(0)
                dao.upsertBinding(
                    WidgetBindingEntity(
                        appWidgetId = widgetId,
                        pairingId = app.container.pairingId.value,
                        surfaceId = surfaceId,
                        createdAtEpochMs = prior?.createdAtEpochMs ?: now,
                        updatedAtEpochMs = now,
                    ),
                )
                WidgetUpdateCoordinator.update(this@WidgetConfigurationActivity, intArrayOf(widgetId))
            }
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            )
            finish()
        }
    }

    private fun isOurWidget(id: Int): Boolean {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return false
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider
        return provider == ComponentName(this, JetpacsWidgetProvider::class.java)
    }

    private companion object {
        val WIDGET_SURFACE = Regex("widget:[A-Za-z0-9][A-Za-z0-9._:/-]*")
    }
}
