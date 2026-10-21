// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JetpacsNavigationTest {
    @Test
    fun recognizedVariantsOverrideLegacyScrollableAndUnknownValuesFallBack() {
        assertEquals(
            JetpacsTabVariant.Navigator,
            resolveJetpacsTabVariant("navigator", scrollable = false),
        )
        assertEquals(
            JetpacsTabVariant.Fixed,
            resolveJetpacsTabVariant("fixed", scrollable = true),
        )
        assertEquals(
            JetpacsTabVariant.Scrollable,
            resolveJetpacsTabVariant("future-mode", scrollable = true),
        )
        assertEquals(
            JetpacsTabVariant.Fixed,
            resolveJetpacsTabVariant(null, scrollable = false),
        )
    }

    @Test
    fun adaptiveTabsUseMeasuredFitAndThreeViewportCutover() {
        assertEquals(
            JetpacsTabVariant.Fixed,
            resolveAdaptiveJetpacsTabVariant(listOf(80, 80, 80, 80), 400),
        )
        assertEquals(
            JetpacsTabVariant.Scrollable,
            resolveAdaptiveJetpacsTabVariant(listOf(150, 150, 150, 150), 200),
        )
        assertEquals(
            JetpacsTabVariant.Navigator,
            resolveAdaptiveJetpacsTabVariant(listOf(151, 151, 151, 151), 200),
        )
    }

    @Test
    fun adaptiveTabsBoundIntrinsicMeasurementAtThreePopupPages() {
        assertEquals(
            MAX_VISIBLE_NAVIGATOR_OPTIONS * 3,
            MAX_MEASURED_ADAPTIVE_TAB_OPTIONS,
        )
        assertEquals(
            null,
            premeasureAdaptiveJetpacsTabVariant(MAX_MEASURED_ADAPTIVE_TAB_OPTIONS),
        )
        assertEquals(
            JetpacsTabVariant.Navigator,
            premeasureAdaptiveJetpacsTabVariant(MAX_MEASURED_ADAPTIVE_TAB_OPTIONS + 1),
        )
    }

    @Test
    fun controlledOptionShapesRejectEmptyAuthoredFields() {
        assertThrows(IllegalArgumentException::class.java) {
            JetpacsTabOption(label = "", value = "preview")
        }
        assertThrows(IllegalArgumentException::class.java) {
            JetpacsTabOption(label = "Preview", value = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            JetpacsSectionOption(label = "", value = "intro", level = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JetpacsSectionOption(label = "Intro", value = "", level = 1)
        }
    }

    @Test
    fun controlledOptionListsFailDeterministicallyBeforeKeyedComposition() {
        // Empty public component lists remain a rendering no-op for compatibility.
        validateJetpacsControlledOptions("Test", emptyList(), value = "unselected")
        validateJetpacsControlledOptions("Test", listOf("one", "two"), value = "two")

        assertThrows(IllegalArgumentException::class.java) {
            validateJetpacsControlledOptions(
                componentName = "Test",
                optionValues = listOf("same", "same"),
                value = "same",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateJetpacsControlledOptions(
                componentName = "Test",
                optionValues = listOf("one", "two"),
                value = "missing",
            )
        }
    }

    @Test
    fun fixedTabsFallBackExactlyWhenEqualCellsWouldBeNarrowerThan48Dp() {
        assertEquals(false, fixedTabsRequireScrollableFallback(4, 192.dp))
        assertEquals(true, fixedTabsRequireScrollableFallback(4, 191.dp))
        assertEquals(false, fixedTabsRequireScrollableFallback(100, Dp.Infinity))
        assertThrows(IllegalArgumentException::class.java) {
            fixedTabsRequireScrollableFallback(-1, 192.dp)
        }
    }

    @Test
    fun sectionBreadcrumbsFollowAuthoredLevelsWithoutInventingMissingParents() {
        val breadcrumbs = jetpacsSectionBreadcrumbs(
            listOf(
                JetpacsSectionOption("Overview", "overview", 1),
                JetpacsSectionOption("Install", "install", 2),
                JetpacsSectionOption("Linux", "linux", 3),
                JetpacsSectionOption("API", "api", 1),
                JetpacsSectionOption("Functions", "functions", 3),
            ),
        )

        assertEquals("Overview", breadcrumbs.getValue("overview"))
        assertEquals("Overview › Install", breadcrumbs.getValue("install"))
        assertEquals("Overview › Install › Linux", breadcrumbs.getValue("linux"))
        assertEquals("API › Functions", breadcrumbs.getValue("functions"))
    }

    @Test
    fun anchoredPopupUsesReadingEdgeAndMovesAboveWhenThatSpaceIsLarger() {
        val ltr = JetpacsAnchoredPopupPositionProvider(LayoutDirection.Ltr)
        assertEquals(
            IntOffset(100, 148),
            ltr.calculatePosition(
                anchorBounds = IntRect(100, 100, 300, 148),
                windowSize = IntSize(400, 600),
                layoutDirection = LayoutDirection.Ltr,
                popupContentSize = IntSize(150, 300),
            ),
        )
        val rtl = JetpacsAnchoredPopupPositionProvider(LayoutDirection.Rtl)
        assertEquals(
            IntOffset(150, 200),
            rtl.calculatePosition(
                anchorBounds = IntRect(100, 500, 300, 548),
                windowSize = IntSize(400, 600),
                layoutDirection = LayoutDirection.Rtl,
                popupContentSize = IntSize(150, 300),
            ),
        )
    }
}
