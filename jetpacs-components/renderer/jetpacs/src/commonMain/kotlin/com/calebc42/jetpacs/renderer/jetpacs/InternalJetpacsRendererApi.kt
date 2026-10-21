// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

/**
 * Marks a declaration that is public only so the screenshot module
 * (`renderer/jetpacs-screenshots`) can compose it. It is not part of the
 * Jetpacs Components renderer API: its shape may change with the fixture
 * that uses it, and no application or other renderer may opt in.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Screenshot fixture, not renderer API; only renderer/jetpacs-screenshots opts in.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalJetpacsRendererApi
