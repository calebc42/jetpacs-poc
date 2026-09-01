// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WidgetActionConfirmationActivity : ComponentActivity() {
    private var face by mutableStateOf<WidgetConfirmation?>(null)
    private val token: String? by lazy { WidgetActionUri.parse(intent?.data) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val opaqueToken = token
        if (opaqueToken == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            face = withContext(Dispatchers.IO) {
                WidgetActionRouter.confirmation(this@WidgetActionConfirmationActivity, opaqueToken)
            }
            if (face == null) finish()
        }
        setContent {
            MaterialTheme {
                face?.let { confirmation ->
                    AlertDialog(
                        onDismissRequest = ::finish,
                        title = confirmation.title?.let { title -> { Text(title) } },
                        text = { Text(confirmation.text) },
                        confirmButton = {
                            TextButton(onClick = { confirm(opaqueToken) }) {
                                Text(confirmation.confirmLabel ?: "OK")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = ::finish) {
                                Text(confirmation.dismissLabel ?: "Cancel")
                            }
                        },
                    )
                }
            }
        }
    }

    private fun confirm(opaqueToken: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                WidgetActionRouter.dispatch(
                    this@WidgetActionConfirmationActivity,
                    opaqueToken,
                    confirmed = true,
                )
            }
            finish()
        }
    }
}
