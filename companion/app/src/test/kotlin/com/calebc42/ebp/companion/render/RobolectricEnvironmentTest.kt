// SPDX-License-Identifier: GPL-3.0-or-later
// RF-1b: the pin that makes the renderer suite's platform honest.
//
// Robolectric's most dangerous failure here is not a crash — it is a
// SILENT DEGRADATION. Without `testOptions { unitTests {
// isIncludeAndroidResources = true } }` the whole suite still runs, still
// goes green, and still reports nothing amiss, but it runs on Android
// 6.0.1 (SDK 23) under the package name `org.robolectric.default`. Every
// renderer assertion would then be about a platform this app declares it
// cannot run on (minSdk 34). Measured, not theorised: removing the block
// flipped SDK_INT from 36 to 23 with no warning emitted.
//
// So the configuration is asserted, not trusted. If this file goes red,
// nothing else in the renderer suite should be believed.
package com.calebc42.ebp.companion.render

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RobolectricEnvironmentTest {

    /** app/build.gradle.kts declares targetSdk 36; Robolectric 4.16.1
     * selects it from the merged manifest with no @Config annotation. A
     * 23 here means the testOptions block went missing. */
    @Test
    fun theSuiteRunsOnTheSdkTheAppTargets() {
        assertEquals(36, Build.VERSION.SDK_INT)
    }

    /** The other half of the same degradation: without the real merged
     * manifest the package is `org.robolectric.default`. */
    @Test
    fun theApplicationIsOursNotRobolectricsDefault() {
        assertEquals("com.calebc42.ebp.companion",
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .packageName)
    }
}
