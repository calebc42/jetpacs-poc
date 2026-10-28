// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from renderer-extensions/jetpacs-material3.json (format 2) by
// tools/gen-renderer-extension-vocabulary.py. DO NOT EDIT: JetpacsMaterial3VocabularyTest pins this projection.
package com.calebc42.jetpacs.material3

import com.calebc42.ebp.renderer.model.vocabulary.EnumDomain
import com.calebc42.ebp.renderer.model.vocabulary.WireEnum

/** Contract enum domain `material3.assist_chip.variant`. */
enum class Material3AssistChipVariant(override val wire: String) : WireEnum {
    Elevated("elevated"),
    ElevatedSuggestion("elevated_suggestion"),
    Flat("flat"),
    Suggestion("suggestion");

    companion object : EnumDomain<Material3AssistChipVariant> {
        override val contractKey: String = "material3.assist_chip.variant"
        override val values: List<Material3AssistChipVariant> get() = entries
    }
}

/** Contract enum domain `material3.split_button.size`. */
enum class Material3SplitButtonSize(override val wire: String) : WireEnum {
    Large("large"),
    Medium("medium"),
    Small("small"),
    Xlarge("xlarge"),
    Xsmall("xsmall");

    companion object : EnumDomain<Material3SplitButtonSize> {
        override val contractKey: String = "material3.split_button.size"
        override val values: List<Material3SplitButtonSize> get() = entries
    }
}

/** Contract enum domain `material3.split_button.variant`. */
enum class Material3SplitButtonVariant(override val wire: String) : WireEnum {
    Elevated("elevated"),
    Filled("filled"),
    Outlined("outlined"),
    Tonal("tonal");

    companion object : EnumDomain<Material3SplitButtonVariant> {
        override val contractKey: String = "material3.split_button.variant"
        override val values: List<Material3SplitButtonVariant> get() = entries
    }
}

/** Contract enum domain `material3.fab.color`. */
enum class Material3FabColor(override val wire: String) : WireEnum {
    Primary("primary"),
    Secondary("secondary"),
    Surface("surface"),
    Tertiary("tertiary");

    companion object : EnumDomain<Material3FabColor> {
        override val contractKey: String = "material3.fab.color"
        override val values: List<Material3FabColor> get() = entries
    }
}

/** Contract enum domain `material3.fab.size`. */
enum class Material3FabSize(override val wire: String) : WireEnum {
    Large("large"),
    Medium("medium"),
    Small("small");

    companion object : EnumDomain<Material3FabSize> {
        override val contractKey: String = "material3.fab.size"
        override val values: List<Material3FabSize> get() = entries
    }
}

/** Contract enum domain `material3.chronometer.style`. */
enum class Material3ChronometerStyle(override val wire: String) : WireEnum {
    Body("body"),
    Caption("caption"),
    Headline("headline"),
    Mono("mono"),
    Title("title");

    companion object : EnumDomain<Material3ChronometerStyle> {
        override val contractKey: String = "material3.chronometer.style"
        override val values: List<Material3ChronometerStyle> get() = entries
    }
}

/** Every generated enum domain, keyed by its contract key. */
val JETPACS_MATERIAL3_ENUMS: Map<String, EnumDomain<*>> = mapOf(
    "material3.assist_chip.variant" to Material3AssistChipVariant,
    "material3.split_button.size" to Material3SplitButtonSize,
    "material3.split_button.variant" to Material3SplitButtonVariant,
    "material3.fab.color" to Material3FabColor,
    "material3.fab.size" to Material3FabSize,
    "material3.chronometer.style" to Material3ChronometerStyle,
)
