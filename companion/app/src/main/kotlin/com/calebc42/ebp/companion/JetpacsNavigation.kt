@file:Suppress("OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
package com.calebc42.ebp.companion

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.ui.NavDisplay
import com.calebc42.ebp.companion.render.RenderNode
import com.calebc42.ebp.companion.render.chromeBackDescriptor
import com.calebc42.ebp.companion.ui.JetpacsCatalogAction
import com.calebc42.ebp.companion.ui.JetpacsChoiceRow
import com.calebc42.jetpacs.core.navigation.JetpacsNavKey
import com.calebc42.jetpacs.core.navigation.openPresentJetpacsSurface
import com.calebc42.jetpacs.core.navigation.pushJetpacsDestination
import com.calebc42.jetpacs.core.navigation.reconcileJetpacsBackStack
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Receiver-owned application navigation.
 *
 * A Surface key selects an EBP surface. The view inside a multi-view surface
 * remains wholly owned by SurfaceStore + `view.switch`; it is never expanded
 * into this Nav3 back stack.
 */
@Suppress("OPT_IN_USAGE")
@Composable
internal fun JetpacsNavHost(
    app: EbpApplication,
    bridge: DeviceBridge,
    onboardingRequired: Boolean,
    onOnboardingComplete: () -> Unit,
    onExit: () -> Unit,
) {
    val pairingId = JETPACS_PAIRING_ID
    val surfaceCatalog by app.appSurfaceCatalog.collectAsState()
    val surfaceIds = surfaceCatalog.surfaceIds
    val settingsRequested by app.settingsOpen.collectAsState()
    val surfaceOpenRequest by app.surfaceOpenRequest.collectAsState()
    val initial = if (onboardingRequired) {
        JetpacsNavKey.Pairing
    } else {
        JetpacsNavKey.Surface(pairingId, "app:hub")
    }
    val backStack = rememberJetpacsNavBackStack(initial)

    fun replaceStack(keys: List<JetpacsNavKey>) {
        backStack.clear()
        backStack.addAll(keys)
    }

    fun push(destination: JetpacsNavKey) {
        val current = backStack.toList()
        val next = pushJetpacsDestination(current, destination)
        if (next != current) replaceStack(next)
    }

    fun popOrExit() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        else onExit()
    }

    // A removed surface can survive in Android saved state. Reconcile keys
    // against the durable EBP cache rather than rendering a ghost document.
    LaunchedEffect(onboardingRequired, pairingId, surfaceCatalog) {
        // The cache is loaded off the main thread. Until its completion
        // barrier arrives, an empty ID list says "not loaded", not "removed".
        if (!onboardingRequired && !surfaceCatalog.loaded) return@LaunchedEffect
        val current = backStack.toList()
        val next = reconcileJetpacsBackStack(
            current,
            pairingId,
            surfaceIds.toSet(),
            onboardingRequired,
        )
        if (next != current) replaceStack(next)
    }

    // SPEC 14.2: this builtin opens receiver-local settings. It does not
    // synthesize an EBP action or disturb a Surface's current_view.
    LaunchedEffect(settingsRequested, onboardingRequired) {
        if (settingsRequested) {
            if (!onboardingRequired) push(JetpacsNavKey.Settings)
            app.dismissSettings()
        }
    }

    // SPEC 14.2: unlike surface.update, surface.open is an explicit user
    // request to select a receiver-owned Surface destination. Wait for the
    // durable cache hydration barrier, then make an absent target a safe
    // no-op. No EBP current_view or view.switched state participates.
    LaunchedEffect(
        surfaceOpenRequest,
        onboardingRequired,
        pairingId,
        surfaceCatalog,
    ) {
        val request = surfaceOpenRequest ?: return@LaunchedEffect
        if (onboardingRequired) {
            app.dismissSurfaceOpen(request.epoch)
            return@LaunchedEffect
        }
        if (request.surfaceId == "companion:settings") {
            push(JetpacsNavKey.Settings)
            app.dismissSurfaceOpen(request.epoch)
            return@LaunchedEffect
        }
        if (!surfaceCatalog.loaded) return@LaunchedEffect
        val current = backStack.toList()
        val next = openPresentJetpacsSurface(
            current,
            pairingId,
            surfaceIds.toSet(),
            request.surfaceId,
        )
        if (next != current) replaceStack(next)
        app.dismissSurfaceOpen(request.epoch)
    }

    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = ::popOrExit,
        // Nav3's default 700 ms crossfade keeps both complete Surface trees
        // composed and measured.  Surface documents can contain hundreds of
        // rows, so switch atomically and preserve the same back-stack policy
        // without paying for two full screens on every navigation.
        transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
        popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
        predictivePopTransitionSpec = {
            EnterTransition.None togetherWith ExitTransition.None
        },
        entryProvider = entryProvider {
            entry<JetpacsNavKey.Pairing> {
                val requiredRoot = onboardingRequired && backStack.size == 1
                val finishOnboarding = dropUnlessResumed {
                    val wasRoot = backStack.size == 1
                    onOnboardingComplete()
                    if (wasRoot) {
                        replaceStack(listOf(JetpacsNavKey.Surface(pairingId, "app:hub")))
                    } else {
                        backStack.removeAt(backStack.lastIndex)
                    }
                }
                OnboardingFlow(
                    onDone = finishOnboarding,
                    onCancel = if (requiredRoot) {
                        null
                    } else {
                        dropUnlessResumed { popOrExit() }
                    },
                )
            }
            entry<JetpacsNavKey.Catalog> {
                SurfaceCatalogScreen(
                    surfaceIds = surfaceIds,
                    catalogLoaded = surfaceCatalog.loaded,
                    connectedFlow = bridge.connected,
                    onSurface = { surfaceId ->
                        push(JetpacsNavKey.Surface(pairingId, surfaceId))
                    },
                    onSettings = { push(JetpacsNavKey.Settings) },
                    onRepair = { push(JetpacsNavKey.Pairing) },
                )
            }
            entry<JetpacsNavKey.Surface> { key ->
                SurfaceDestination(
                    surfaceId = key.surfaceId,
                    surfaceFlow = app.appSurface(key.surfaceId),
                    connectedFlow = bridge.connected,
                    bridge = bridge,
                    onRepair = { push(JetpacsNavKey.Pairing) },
                )
            }
            entry<JetpacsNavKey.Settings> {
                JetpacsSettingsScreen(
                    bridge = bridge,
                    onBack = ::popOrExit,
                    onOpenOnboarding = { push(JetpacsNavKey.Pairing) },
                )
            }
        },
    )
}

/**
 * Navigation3's Android convenience overload intentionally returns
 * NavBackStack<NavKey>. Jetpacs has a closed serializable key hierarchy, so
 * retain that concrete type and its generated sealed serializer end-to-end.
 */
@Composable
private fun rememberJetpacsNavBackStack(
    vararg elements: JetpacsNavKey,
): NavBackStack<JetpacsNavKey> = rememberSerializable(
    serializer = NavBackStackSerializer<JetpacsNavKey>(),
) {
    NavBackStack(*elements)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SurfaceCatalogScreen(
    surfaceIds: List<String>,
    catalogLoaded: Boolean,
    connectedFlow: StateFlow<Boolean>,
    onSurface: (String) -> Unit,
    onSettings: () -> Unit,
    onRepair: () -> Unit,
) {
    val connected by connectedFlow.collectAsState()
    val context = LocalContext.current
    val openSettings = dropUnlessResumed { onSettings() }
    val openEmacs = dropUnlessResumed { openEmacsOrExplain(context) }
    val repair = dropUnlessResumed { onRepair() }
    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Jetpacs") },
                actions = {
                    IconButton(onClick = openSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (surfaceIds.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (catalogLoaded) {
                    WaitingForEmacs(
                        onRepair = repair,
                    )
                } else {
                    Text(
                        "Loading cached apps…",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        } else {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(300.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding
            ) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (connected) "Connected apps" else "Cached apps — Emacs is offline",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(
                    count = surfaceIds.size,
                    key = { surfaceIds[it] }
                ) { index ->
                    val surfaceId = surfaceIds[index]
                    val openSurface = dropUnlessResumed { onSurface(surfaceId) }
                    JetpacsCatalogAction(
                        headline = surfaceTitle(surfaceId),
                        supportingText = surfaceId,
                        onClick = openSurface,
                        leadingContent = {
                            Icon(Icons.Default.Apps, contentDescription = null)
                        },
                    )
                }
            }
        }
    }
}

private fun surfaceTitle(surfaceId: String): String =
    surfaceId.substringAfter(':')
        .split('.', '-', '_', '/')
        .filter(String::isNotEmpty)
        .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }
        .ifEmpty { surfaceId }

@Composable
private fun SurfaceDestination(
    surfaceId: String,
    surfaceFlow: StateFlow<JsonObject?>,
    connectedFlow: StateFlow<Boolean>,
    bridge: DeviceBridge,
    onRepair: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        SurfaceDocumentHost(surfaceId, surfaceFlow, bridge, onRepair)
        ConnectionStatusHost(surfaceFlow, connectedFlow)
    }
}

@Composable
private fun SurfaceDocumentHost(
    surfaceId: String,
    surfaceFlow: StateFlow<JsonObject?>,
    bridge: DeviceBridge,
    onRepair: () -> Unit,
) {
    val shown by surfaceFlow.collectAsState()
    val surfaceIncarnations by
        bridge.surfacePresentationIncarnations.collectAsState()
    when (val spec = shown) {
        null -> {
            val connected by bridge.connected.collectAsState()
            val context = LocalContext.current
            val openEmacs = dropUnlessResumed { openEmacsOrExplain(context) }
            val repair = dropUnlessResumed { onRepair() }
            WaitingForEmacs(
                onRepair = repair,
            )
        }
        else -> key(surfaceIncarnations[surfaceId] ?: Long.MIN_VALUE) {
            // An authored in-surface back arrow outranks Nav3 back. The
            // descriptor runs through the exact same view.switch path as a
            // tap; when absent, NavDisplay pops to the receiver catalog.
            val back = chromeBackDescriptor(spec)
            val entryLifecycle by
                LocalLifecycleOwner.current.lifecycle.currentStateAsState()
            BackHandler(
                enabled = back != null && entryLifecycle == Lifecycle.State.RESUMED,
            ) {
                back?.let { bridge.action(surfaceId, it) }
            }
            RenderNode(
                spec,
                surfaceId,
                bridge,
                configuration = CompanionRenderer.composeConfiguration,
            )
        }
    }
}

/** Show receiver-owned offline chrome without recomposing the surface tree. */
@Composable
private fun ConnectionStatusHost(
    surfaceFlow: StateFlow<JsonObject?>,
    connectedFlow: StateFlow<Boolean>,
) {
    val shown by surfaceFlow.collectAsState()
    val connected by connectedFlow.collectAsState()
    if (shown == null || connected) return

    val context = LocalContext.current
    val openEmacs = dropUnlessResumed { openEmacsOrExplain(context) }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.Cable, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text("Emacs is offline", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Showing the last screen Emacs sent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.75f),
                    )
                }
                TextButton(onClick = openEmacs) {
                    Text("Open Emacs", color = MaterialTheme.colorScheme.inversePrimary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JetpacsSettingsScreen(
    bridge: DeviceBridge,
    onBack: () -> Unit,
    onOpenOnboarding: () -> Unit,
) {
    var narrowing by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(bridge.completionNarrowing)
    }
    val navigateBack = dropUnlessResumed { onBack() }
    val openOnboarding = dropUnlessResumed { onOpenOnboarding() }
    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
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
                        com.calebc42.ebp.wire.CompletionNarrowing.STRICT,
                        "Strict",
                        "Candidates start with what you typed",
                    ),
                    Triple(
                        com.calebc42.ebp.wire.CompletionNarrowing.CONTAINS,
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
            Text("Installation", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = openOnboarding) {
                Text("Set up or repair Jetpacs")
            }
        }
    }
}

private fun openEmacsOrExplain(context: Context) {
    if (!openAndroidEmacs(context)) {
        Toast.makeText(context, "Android Emacs is not installed", Toast.LENGTH_SHORT).show()
    }
}
