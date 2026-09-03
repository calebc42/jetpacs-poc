// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.jetpacs

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.ebp.wire.CompletionOfferView
import com.calebc42.ebp.renderer.model.CandidateDocument
import com.calebc42.ebp.renderer.model.CompletionCandidate
import com.calebc42.ebp.renderer.model.CompletionOffer
import com.calebc42.ebp.renderer.model.DiagnosticRange
import com.calebc42.ebp.renderer.model.DiagnosticSet
import com.calebc42.ebp.renderer.model.EldocLine
import com.calebc42.ebp.renderer.model.EditorSyncPhase
import com.calebc42.ebp.renderer.model.FontifyRun
import com.calebc42.ebp.renderer.model.FontifySet
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "compact", widthDp = 400, heightDp = 420)
@Preview(name = "dark", widthDp = 400, heightDp = 420,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "large-text", widthDp = 400, heightDp = 420, fontScale = 1.5f)
@Composable
private fun JetpacsListItemRows() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            JetpacsListItem(title = "Plain row")
            JetpacsListItem(
                title = "inbox",
                subtitle = "Notebooks",
                leading = { BasicText("[]", style = JetpacsTheme.typography.code) },
                trailing = { BasicText("3", style = JetpacsTheme.typography.fieldLabel) },
                onClick = {},
            )
            JetpacsListItem(
                title = "With an overline",
                overline = "YESTERDAY",
                subtitle = "Two supporting lines keep their own bound so a long "
                    + "one truncates instead of pushing the trailing edge away.",
                subtitleMaxLines = 2,
                onClick = {},
            )
            JetpacsListItem(title = "Selected", subtitle = "Tab role", selected = true, onClick = {})
            JetpacsListItem(title = "Disabled", subtitle = "No target", enabled = false, onClick = {})
        }
    }
}

@PreviewTest
@Preview(name = "compact", widthDp = 400, heightDp = 620)
@Preview(name = "expanded", widthDp = 900, heightDp = 620)
@Composable
private fun JetpacsComponentsAcrossWidths() {
    JetpacsComponentsGallery()
}

@PreviewTest
@Preview(
    name = "dark",
    widthDp = 400,
    heightDp = 620,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun JetpacsComponentsDark() {
    JetpacsComponentsGallery()
}

@PreviewTest
@Preview(name = "large-text", widthDp = 400, heightDp = 720, fontScale = 1.5f)
@Composable
private fun JetpacsComponentsLargeText() {
    JetpacsComponentsGallery()
}

@PreviewTest
@Preview(name = "focus-hover", widthDp = 400, heightDp = 260)
@Composable
private fun JetpacsComponentsFocusAndHover() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            JetpacsActionFocusFixture("Keyboard focus")
            JetpacsChoiceHoverFixture("Pointer hover")
        }
    }
}

@PreviewTest
@Preview(name = "compact", widthDp = 400, heightDp = 280)
@Preview(name = "expanded", widthDp = 900, heightDp = 280)
@Composable
private fun JetpacsTabsAcrossWidths() {
    JetpacsTabsGallery()
}

@PreviewTest
@Preview(
    name = "dark",
    widthDp = 400,
    heightDp = 280,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun JetpacsTabsDark() {
    JetpacsTabsGallery()
}

@PreviewTest
@Preview(name = "large-text", widthDp = 400, heightDp = 360, fontScale = 1.5f)
@Composable
private fun JetpacsTabsLargeText() {
    JetpacsTabsGallery()
}

@PreviewTest
@Preview(name = "compact", widthDp = 400, heightDp = 520)
@Preview(name = "expanded", widthDp = 900, heightDp = 520)
@Preview(name = "large-text", widthDp = 400, heightDp = 640, fontScale = 1.5f)
@Preview(
    name = "dark",
    widthDp = 400,
    heightDp = 520,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun JetpacsNavigationModes() {
    JetpacsNavigationGallery()
}

@PreviewTest
@Preview(name = "rtl", widthDp = 400, heightDp = 520)
@Composable
private fun JetpacsNavigationModesRtl() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        JetpacsNavigationGallery()
    }
}

@PreviewTest
@Preview(name = "long-popup", widthDp = 400, heightDp = 440)
@Composable
private fun JetpacsNavigatorPopupOpen() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
        ) {
            JetpacsNavigatorPopupContent(
                options = (1..20).map {
                    JetpacsNavigatorOption(
                        label = "Authored destination $it",
                        value = "destination-$it",
                        accessibleLabel = "Authored destination $it",
                    )
                },
                value = "destination-11",
                semantics = JetpacsNavigatorSemantics.Tabs,
                enabled = true,
                onOptionClick = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "compact", widthDp = 400, heightDp = 760)
@Preview(name = "expanded", widthDp = 900, heightDp = 760)
@Composable
private fun JetpacsTextFieldsAcrossWidths() {
    JetpacsTextFieldGallery()
}

@PreviewTest
@Preview(
    name = "dark",
    widthDp = 400,
    heightDp = 760,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun JetpacsTextFieldsDark() {
    JetpacsTextFieldGallery()
}

@PreviewTest
@Preview(name = "large-text", widthDp = 400, heightDp = 960, fontScale = 1.5f)
@Composable
private fun JetpacsTextFieldsLargeText() {
    JetpacsTextFieldGallery()
}

@PreviewTest
@Preview(name = "focus-secure", widthDp = 400, heightDp = 300)
@Composable
private fun JetpacsTextFieldsFocusAndSecure() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            JetpacsTextFieldFocusFixture(
                state = rememberTextFieldState("Active edit"),
                label = "Keyboard focus",
            )
            JetpacsTextField(
                state = rememberTextFieldState(),
                secure = true,
                label = "One-time secret",
                placeholder = "Never retained",
                leadingDecoration = { JetpacsFieldGlyph("lock") },
            )
        }
    }
}

@PreviewTest
@Preview(name = "rtl", widthDp = 400, heightDp = 420)
@Composable
private fun JetpacsTextFieldsRtl() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ProvideJetpacsTheme(null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(JetpacsTheme.colors.background)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                JetpacsTextField(
                    state = rememberTextFieldState("١٢٣"),
                    label = "المبلغ",
                    prefix = "$",
                    suffix = ".00",
                    leadingDecoration = { JetpacsFieldGlyph("search") },
                    trailingDecoration = { JetpacsFieldGlyph("clear") },
                )
                JetpacsTextField(
                    state = rememberTextFieldState("قيمة غير صالحة"),
                    label = "اسم مساحة العمل",
                    supportingText = "هذا الاسم مستخدم بالفعل",
                    isError = true,
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "compact", widthDp = 400, heightDp = 820)
@Preview(name = "expanded", widthDp = 900, heightDp = 820)
@Composable
private fun JetpacsEditorsAcrossWidths() {
    JetpacsEditorGallery()
}

@PreviewTest
@Preview(
    name = "dark",
    widthDp = 400,
    heightDp = 820,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun JetpacsEditorsDark() {
    JetpacsEditorGallery()
}

@PreviewTest
@Preview(name = "large-text", widthDp = 400, heightDp = 1040, fontScale = 1.5f)
@Composable
private fun JetpacsEditorsLargeText() {
    JetpacsEditorGallery()
}

@PreviewTest
@Preview(name = "focus-read-only", widthDp = 400, heightDp = 400)
@Composable
private fun JetpacsEditorsFocusAndReadOnly() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            JetpacsEditorFocusFixture(
                rememberTextFieldState("(message \"Focused\")"),
            )
            JetpacsEditor(
                state = rememberTextFieldState("This draft is read-only."),
                readOnly = true,
                lineLimits = TextFieldLineLimits.MultiLine(2, 3),
            )
        }
    }
}

@PreviewTest
@Preview(name = "rtl", widthDp = 400, heightDp = 480)
@Composable
private fun JetpacsEditorsRtl() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ProvideJetpacsTheme(null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(JetpacsTheme.colors.background)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                JetpacsEditorToolbarFixture()
                JetpacsEditor(
                    state = rememberTextFieldState("السطر الأول\nالسطر الثاني\nالسطر الثالث"),
                    lineNumbers = true,
                    lineLimits = TextFieldLineLimits.MultiLine(4, 5),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "offline-stale", widthDp = 400, heightDp = 430)
@Composable
private fun JetpacsSynchronizedEditorStates() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("OFFLINE", style = JetpacsTheme.typography.panelLabel)
            JetpacsEditor(
                state = rememberTextFieldState("Visible synchronized draft"),
                readOnly = true,
                lineLimits = TextFieldLineLimits.MultiLine(2, 3),
            )
            JetpacsEditorSyncStatus(EditorSyncPhase.OFFLINE_READ_ONLY)
            BasicText("STALE", style = JetpacsTheme.typography.panelLabel)
            JetpacsEditor(
                state = rememberTextFieldState("Winning remote value"),
                readOnly = true,
                lineLimits = TextFieldLineLimits.MultiLine(2, 3),
            )
            JetpacsEditorSyncStatus(EditorSyncPhase.STALE)
        }
    }
}

@PreviewTest
@Preview(name = "compact", widthDp = 400, heightDp = 920)
@Preview(name = "expanded", widthDp = 900, heightDp = 720)
@Composable
private fun JetpacsEditorToolingAcrossWidths() {
    JetpacsEditorToolingGallery()
}

@PreviewTest
@Preview(
    name = "dark",
    widthDp = 400,
    heightDp = 920,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun JetpacsEditorToolingDark() {
    JetpacsEditorToolingGallery()
}

@PreviewTest
@Preview(name = "large-text", widthDp = 400, heightDp = 1120, fontScale = 1.5f)
@Composable
private fun JetpacsEditorToolingLargeText() {
    JetpacsEditorToolingGallery()
}

@PreviewTest
@Preview(name = "rtl", widthDp = 400, heightDp = 920)
@Composable
private fun JetpacsEditorToolingRtl() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        JetpacsEditorToolingGallery()
    }
}

/** Deterministic first-slice gallery shared by all baseline configurations. */
@Composable
private fun JetpacsComponentsGallery() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            JetpacsAction("Run ordinary action", onClick = {})
            JetpacsAction("Disabled action", onClick = {}, enabled = false)
            JetpacsChoice("Checked choice", checked = true, onCheckedChange = {})
            JetpacsChoice("Unchecked choice", checked = false, onCheckedChange = {})
            JetpacsPanel("STATUS") {
                BasicText("Ready", style = JetpacsTheme.typography.choice)
                JetpacsAction("Nested action", onClick = {})
            }
        }
    }
}

/** Fixed and scrollable Tabs variants shared by visual-regression previews. */
@Composable
private fun JetpacsTabsGallery() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("FIXED", style = JetpacsTheme.typography.panelLabel)
            JetpacsTabs(
                options = listOf(
                    JetpacsTabOption("Preview", "preview"),
                    JetpacsTabOption("Visual", "visual"),
                    JetpacsTabOption("Lisp", "lisp"),
                    JetpacsTabOption("Source", "source"),
                ),
                value = "visual",
                onValueChange = {},
            )
            BasicText("SCROLLABLE", style = JetpacsTheme.typography.panelLabel)
            JetpacsTabs(
                options = listOf(
                    JetpacsTabOption("Overview", "overview"),
                    JetpacsTabOption("Anatomy", "anatomy"),
                    JetpacsTabOption("Behavior", "behavior"),
                    JetpacsTabOption("Accessibility", "accessibility"),
                    JetpacsTabOption("Examples", "examples"),
                ),
                value = "accessibility",
                onValueChange = {},
                scrollable = true,
            )
        }
    }
}

/** Long peer-view and document navigation fixtures for adaptive regression. */
@Composable
private fun JetpacsNavigationGallery() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("NAVIGATOR TABS", style = JetpacsTheme.typography.panelLabel)
            JetpacsTabs(
                options = listOf(
                    JetpacsTabOption("Overview", "overview"),
                    JetpacsTabOption("Anatomy", "anatomy"),
                    JetpacsTabOption("Behavior", "behavior"),
                    JetpacsTabOption("Accessibility", "accessibility"),
                    JetpacsTabOption("Examples", "examples"),
                ),
                value = "accessibility",
                onValueChange = {},
                variant = JetpacsTabVariant.Navigator,
            )
            BasicText("ADAPTIVE TABS", style = JetpacsTheme.typography.panelLabel)
            JetpacsTabs(
                options = (1..12).map {
                    JetpacsTabOption("Long authored view $it", "view-$it")
                },
                value = "view-7",
                onValueChange = {},
                variant = JetpacsTabVariant.Adaptive,
            )
            BasicText("SECTION NAVIGATOR", style = JetpacsTheme.typography.panelLabel)
            JetpacsSectionNavigator(
                options = listOf(
                    JetpacsSectionOption("Overview", "overview", 1),
                    JetpacsSectionOption("Install", "install", 2),
                    JetpacsSectionOption("Linux", "linux", 3),
                    JetpacsSectionOption("API", "api", 1),
                    JetpacsSectionOption("Functions", "functions", 2),
                ),
                value = "linux",
                onValueChange = {},
            )
        }
    }
}

/** Text-field states that are safe to retain as checked-in visual evidence. */
@Composable
private fun JetpacsTextFieldGallery() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            JetpacsTextField(
                state = rememberTextFieldState(),
                label = "Command text",
                placeholder = "Type a command",
            )
            JetpacsTextField(
                state = rememberTextFieldState("42"),
                variant = JetpacsTextFieldVariant.Filled,
                label = "Amount",
                prefix = "$",
                suffix = ".00",
                leadingDecoration = { JetpacsFieldGlyph("search") },
                trailingDecoration = { JetpacsFieldGlyph("clear") },
            )
            JetpacsTextField(
                state = rememberTextFieldState("taken"),
                label = "Workspace name",
                supportingText = "That name is already in use",
                isError = true,
            )
            JetpacsTextField(
                state = rememberTextFieldState("Read from Emacs"),
                label = "Managed value",
                enabled = false,
            )
            JetpacsTextField(
                state = rememberTextFieldState("(message \"Jetpacs\")"),
                label = "Elisp",
                monospace = true,
                outputTransformation = JetpacsSyntaxOutputTransformation(
                    "elisp",
                    JetpacsTheme.syntax,
                ),
                lineLimits = TextFieldLineLimits.MultiLine(2, 3),
            )
            JetpacsTextField(
                state = rememberTextFieldState(),
                secure = true,
                label = "One-time secret",
                placeholder = "Never retained",
                leadingDecoration = { JetpacsFieldGlyph("lock") },
            )
        }
    }
}

/** Local-editor states that are deterministic and contain no synchronized data. */
@Composable
private fun JetpacsEditorGallery() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            JetpacsEditorToolbarFixture()
            JetpacsEditor(
                state = rememberTextFieldState(
                    "; Local editor\n(defun greet (name)\n  (message \"Hello %s\" name))\n",
                ),
                lineNumbers = true,
                outputTransformation = JetpacsSyntaxOutputTransformation(
                    "elisp",
                    JetpacsTheme.syntax,
                ),
                lineLimits = TextFieldLineLimits.MultiLine(5, 6),
            )
            JetpacsEditor(
                state = rememberTextFieldState("Read-only notes stay selectable."),
                readOnly = true,
                lineLimits = TextFieldLineLimits.MultiLine(2, 3),
            )
            JetpacsEditor(
                state = rememberTextFieldState("Chromeless scratch buffer"),
                chromeless = true,
                lineLimits = TextFieldLineLimits.MultiLine(2, 3),
            )
        }
    }
}

/** Deterministic authoritative annotations, status, completion, and lazy docs. */
@Composable
private fun JetpacsEditorToolingGallery() {
    ProvideJetpacsTheme(null) {
        val text = "(defun print-value (value)\n  (message \"%s\" value))"
        val functionStart = text.indexOf("print-value")
        val warningStart = text.lastIndexOf("value")
        val fontify = FontifySet(
            session = "fixture",
            sequence = 4,
            text = text,
            runs = listOf(
                FontifyRun(1, 6, "keyword"),
                FontifyRun(functionStart, functionStart + "print-value".length, "function"),
            ),
        )
        val diagnostic = DiagnosticRange(
            warningStart,
            warningStart + "value".length,
            "warning",
            "Value may be unused",
        )
        val diagnostics = DiagnosticSet(
            session = "fixture",
            sequence = 4,
            text = text,
            diagnostics = listOf(diagnostic),
        )
        val diagnosticColors = JetpacsDiagnosticColors(
            error = JetpacsTheme.colors.error,
            warning = JetpacsTheme.syntax.number,
            info = JetpacsTheme.colors.accent,
            hint = JetpacsTheme.colors.mutedContent,
        )
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("AUTHORITATIVE TOOLING", style = JetpacsTheme.typography.panelLabel)
            JetpacsEditorToolbarFixture()
            JetpacsEditor(
                state = rememberTextFieldState(text),
                lineNumbers = true,
                outputTransformation = JetpacsAnnotationOutputTransformation(
                    fontify,
                    diagnostics,
                    "elisp",
                    JetpacsTheme.syntax,
                    diagnosticColors,
                ),
                lineLimits = TextFieldLineLimits.MultiLine(4, 5),
            )
            JetpacsEditorToolingStatus(diagnostic, null, diagnosticColors)
            JetpacsEditorCompletion(
                offer = CompletionOffer(
                    prefix = "pri",
                    candidates = listOf(
                        CompletionCandidate("print", "built-in", "print()", "function"),
                        CompletionCandidate("printf", "function", "printf()", "method"),
                        CompletionCandidate("priority", "variable", "priority", "variable"),
                    ),
                    session = "fixture",
                    sequence = 4,
                    cursor = 3,
                    epoch = 7,
                ),
                view = CompletionOfferView("pri", "", active = true),
                document = CandidateDocument(
                    index = 0,
                    text = "Print a formatted value to the current output stream.",
                    epoch = 7,
                ),
                narrowing = CompletionNarrowing.STRICT,
                onSelect = {},
                onRequestDocument = { _, _ -> },
            )
            JetpacsEditorToolingStatus(
                diagnostic = null,
                eldoc = EldocLine("fixture", 4, "message: (message FORMAT &rest ARGS)"),
                diagnosticColors = diagnosticColors,
            )
        }
    }
}

@PreviewTest
@Preview(name = "compact", widthDp = 400, heightDp = 620)
@Preview(name = "dark", widthDp = 400, heightDp = 620,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "large-text", widthDp = 400, heightDp = 620, fontScale = 1.5f)
@Composable
private fun JetpacsMenuGroupedPopup() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
        ) {
            JetpacsMenuPopupContent(
                groups = listOf(
                    JetpacsMenuGroup(
                        label = "Add",
                        items = listOf(
                            JetpacsMenuItem("Insert above", icon = "vertical_align_top"),
                            JetpacsMenuItem("Insert below", icon = "vertical_align_bottom"),
                        ),
                    ),
                    JetpacsMenuGroup(
                        label = "Clipboard",
                        items = listOf(
                            JetpacsMenuItem("Cut", icon = "content_cut"),
                            JetpacsMenuItem(
                                "Paste under",
                                icon = "content_paste",
                                enabled = false,
                            ),
                        ),
                    ),
                ),
                onSelect = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "compact", widthDp = 400, heightDp = 480)
@Preview(name = "dark", widthDp = 400, heightDp = 480,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "large-text", widthDp = 400, heightDp = 480, fontScale = 1.5f)
@Composable
private fun JetpacsMenuRichItems() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
        ) {
            JetpacsMenuPopupContent(
                items = listOf(
                    JetpacsMenuItem(
                        "Edit",
                        icon = "edit",
                        supportingText = "Edit mode",
                        checked = true,
                        checkedIcon = "check",
                    ),
                    JetpacsMenuItem("Settings", icon = "settings", checked = false),
                    JetpacsMenuItem(
                        "Termux home",
                        icon = "home",
                        supportingText = "/data/data/com.termux/files/home",
                    ),
                    JetpacsMenuItem("Home", trailingIcon = "home"),
                    JetpacsMenuItem("Send feedback", icon = "email", trailingText = "F11"),
                ),
                onSelect = {},
            )
        }
    }
}
