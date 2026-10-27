// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.jetpacs.companion.host.DeviceBridge
import com.calebc42.jetpacs.companion.host.ExperimentalDesignRuntime
import com.calebc42.jetpacs.material3.ui.JetpacsChoiceRow

/** The Companion's own settings: completion narrowing, experiments, repair. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JetpacsSettingsScreen(
    bridge: DeviceBridge,
    onBack: () -> Unit,
    onOpenOnboarding: () -> Unit,
) {
    var narrowing by remember { mutableStateOf(bridge.completionNarrowing) }
    val designRuntimeEnabled by
        bridge.experimentalDesignRuntimeEnabled.collectAsState()
    val navigateBack = dropUnlessResumed { onBack() }
    val openOnboarding = dropUnlessResumed { onOpenOnboarding() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Jetpacs Settings") },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(20.dp),
        ) {
            Text("Completion narrowing", style = MaterialTheme.typography.titleSmall)
            Text(
                "How typing filters an open completion list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.selectableGroup()) {
                listOf(
                    Triple(
                        CompletionNarrowing.STRICT,
                        "Strict",
                        "Candidates start with what you typed",
                    ),
                    Triple(
                        CompletionNarrowing.CONTAINS,
                        "Contains",
                        "Candidates match anywhere",
                    ),
                ).forEach { (mode, label, summary) ->
                    JetpacsChoiceRow(
                        label = label,
                        supportingText = summary,
                        selected = narrowing == mode,
                        onClick = {
                            bridge.completionNarrowing = mode
                            narrowing = mode
                        },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Experiments", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(ExperimentalDesignRuntime.LABEL)
                    Text(
                        "Uses bounded Elisp-authored Foundation styles. " +
                            "Changing this reconnects Emacs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = designRuntimeEnabled,
                    onCheckedChange = bridge::setExperimentalDesignRuntimeEnabled,
                )
            }
            // The platform's own section, if it has one (the bundled engine's).
            LocalHostUiPlatform.current.ExtraSettings(bridge)
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Installation", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = openOnboarding) {
                Text("Set up or repair Jetpacs")
            }
        }
    }
}
