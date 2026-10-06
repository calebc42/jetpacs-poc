// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.renderer.compose.ComposeComponentRegistry
import com.calebc42.ebp.renderer.model.vocabulary.NodeMemberSet
// One generated member set per node type; the registry pins each component to its own.
import com.calebc42.ebp.renderer.model.vocabulary.generated.*
import kotlinx.serialization.json.JsonObject

/**
 * The Material canonical tier as components: one object per node type the
 * app profile advertises (the 49 EBP nodes and the five `material3.*`
 * extension nodes), each declaring the generated member set it reads through
 * and delegating to the existing Material render function.
 *
 * Bodies stay in `ContentNodes.kt`, `InputNodes.kt`, `LayoutNodes.kt`,
 * `VisualizationNodes.kt` and `Renderer.kt`; this file is the dispatch table
 * `RenderNodeContent` consults after extension and scoped-override lookup.
 */
internal abstract class MaterialComponent(
    final override val nodeType: String,
    final override val members: NodeMemberSet,
) : MaterialNodeComponent

internal object MaterialTextComponent : MaterialComponent("text", TextMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderText(node, modifier)
}

internal object MaterialRichTextComponent : MaterialComponent("rich_text", RichTextMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderRichText(node, context.ctx, modifier)
}

internal object MaterialIconComponent : MaterialComponent("icon", IconMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderIcon(node, modifier)
}

internal object MaterialBadgeComponent : MaterialComponent("badge", BadgeMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderBadge(node, context.ctx, modifier)
}

internal object MaterialTooltipComponent : MaterialComponent("tooltip", TooltipMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderTooltip(node, context.ctx, modifier)
}

internal object MaterialImageComponent : MaterialComponent("image", ImageMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderImage(node, modifier)
}

internal object MaterialSectionHeaderComponent : MaterialComponent("section_header", SectionHeaderMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderSectionHeader(node, context.ctx, modifier)
}

internal object MaterialEmptyStateComponent : MaterialComponent("empty_state", EmptyStateMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderEmptyState(node, context.ctx, modifier)
}

internal object MaterialProgressComponent : MaterialComponent("progress", ProgressMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderProgress(node, modifier)
}

internal object MaterialDateStampComponent : MaterialComponent("date_stamp", DateStampMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderDateStamp(node, modifier)
}

internal object MaterialRowComponent : MaterialComponent("row", RowMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderRow(node, context.ctx, modifier)
}

internal object MaterialColumnComponent : MaterialComponent("column", ColumnMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderColumn(node, context.ctx, modifier)
}

internal object MaterialFlowRowComponent : MaterialComponent("flow_row", FlowRowMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderFlowRow(node, context.ctx, modifier)
}

internal object MaterialBoxComponent : MaterialComponent("box", BoxMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderBox(node, context.ctx, modifier)
}

internal object MaterialSurfaceComponent : MaterialComponent("surface", SurfaceMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderSurfaceNode(node, context.ctx, modifier)
}

internal object MaterialLazyColumnComponent : MaterialComponent("lazy_column", LazyColumnMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderLazyColumn(node, context.ctx, modifier)
}

internal object MaterialVariantHostComponent : MaterialComponent("variant_host", VariantHostMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderVariantHost(node, context.ctx, modifier)
}

internal object MaterialCardComponent : MaterialComponent("card", CardMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderCard(node, context.ctx, modifier)
}

internal object MaterialCollapsibleComponent : MaterialComponent("collapsible", CollapsibleMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderCollapsible(node, context.ctx, modifier)
}

internal object MaterialTabsComponent : MaterialComponent("tabs", TabsMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderTabs(node, context.ctx, modifier)
}

internal object MaterialTabSelectorComponent : MaterialComponent("tab_selector", TabSelectorMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderTabSelector(node, context.ctx, modifier)
}

internal object MaterialTableComponent : MaterialComponent("table", TableMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderTable(node, context.ctx, modifier)
}

internal object MaterialPaneScaffoldComponent : MaterialComponent("pane_scaffold", PaneScaffoldMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderPaneScaffold(node, context.ctx, modifier)
}

internal object MaterialReorderableListComponent :
    MaterialComponent("reorderable_list", ReorderableListMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderReorderableList(node, context.ctx, modifier)
}

internal object MaterialChartComponent : MaterialComponent("chart", ChartMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderChart(node, context.ctx, modifier)
}

internal object MaterialCanvasComponent : MaterialComponent("canvas", CanvasMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderCanvas(node, modifier)
}

internal object MaterialMonthGridComponent : MaterialComponent("month_grid", MonthGridMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderMonthGrid(node, context.ctx, modifier)
}

internal object MaterialSpacerComponent : MaterialComponent("spacer", SpacerMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        Spacer(
            modifier
                .width((UniversalMembers.Width.read(node)?.let(::safeDp) ?: 0f).dp)
                .height((UniversalMembers.Height.read(node)?.let(::safeDp) ?: 0f).dp),
        )
}

internal object MaterialDividerComponent : MaterialComponent("divider", DividerMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        HorizontalDivider(modifier = modifier)
}

internal object MaterialScaffoldComponent : MaterialComponent("scaffold", ScaffoldMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderScaffold(node, context.ctx, modifier)
}

internal object MaterialEditorComponent : MaterialComponent("editor", EditorMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderEditor(node, context.ctx, modifier)
}

internal object MaterialButtonComponent : MaterialComponent("button", ButtonMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderButton(node, context.ctx, modifier)
}

internal object MaterialIconButtonComponent : MaterialComponent("icon_button", IconButtonMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderIconButton(node, context.ctx, modifier)
}

internal object MaterialSplitButtonComponent :
    MaterialComponent("material3.split_button", Material3SplitButtonMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderSplitButton(node, context.ctx, modifier)
}

internal object MaterialNavigationRailComponent : MaterialComponent("navigation_rail", NavigationRailMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderNavigationRail(node, context.ctx, modifier)
}

internal object MaterialSearchBarComponent : MaterialComponent("search_bar", SearchBarMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderSearchBar(node, context.ctx, modifier)
}

internal object MaterialDropdownComponent : MaterialComponent("dropdown", DropdownMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderDropdown(node, context.ctx, modifier)
}

internal object MaterialSegmentedButtonComponent :
    MaterialComponent("segmented_button", SegmentedButtonMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderSegmentedButton(node, context.ctx, modifier)
}

internal object MaterialAppBarRowComponent :
    MaterialComponent("material3.app_bar_row", Material3AppBarRowMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderAppBarStrip(node, context.ctx, modifier, vertical = false)
}

internal object MaterialAppBarColumnComponent :
    MaterialComponent("material3.app_bar_column", Material3AppBarColumnMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderAppBarStrip(node, context.ctx, modifier, vertical = true)
}

internal object MaterialCarouselComponent : MaterialComponent("carousel", CarouselMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderCarousel(node, context.ctx, modifier)
}

internal object MaterialFabMenuComponent : MaterialComponent("material3.fab_menu", Material3FabMenuMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderFabMenu(node, context.ctx, modifier)
}

internal object MaterialButtonGroupComponent : MaterialComponent("button_group", ButtonGroupMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderButtonGroup(node, context.ctx, modifier)
}

internal object MaterialLazyGridComponent : MaterialComponent("lazy_grid", LazyGridMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderLazyGrid(node, context.ctx, modifier)
}

internal object MaterialChipComponent : MaterialComponent("chip", ChipMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderChip(node, context.ctx, modifier)
}

internal object MaterialAssistChipComponent :
    MaterialComponent("material3.assist_chip", Material3AssistChipMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderAssistChip(node, context.ctx, modifier)
}

internal object MaterialMenuComponent : MaterialComponent("menu", MenuMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderMenu(node, context.ctx, modifier)
}

internal object MaterialCheckboxComponent : MaterialComponent("checkbox", CheckboxMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderCheckbox(node, context.ctx, modifier)
}

internal object MaterialSwitchComponent : MaterialComponent("switch", SwitchMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderSwitch(node, context.ctx, modifier)
}

internal object MaterialEnumListComponent : MaterialComponent("enum_list", EnumListMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderEnumList(node, context.ctx, modifier)
}

internal object MaterialSliderComponent : MaterialComponent("slider", SliderMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderSlider(node, context.ctx, modifier)
}

internal object MaterialDateButtonComponent : MaterialComponent("date_button", DateButtonMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderDateButton(node, context.ctx, modifier)
}

internal object MaterialTimeButtonComponent : MaterialComponent("time_button", TimeButtonMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderTimeButton(node, context.ctx, modifier)
}

internal object MaterialTextInputComponent : MaterialComponent("text_input", TextInputMembers) {
    @Composable override fun render(node: JsonObject, context: MaterialNodeRenderContext, modifier: Modifier) =
        RenderTextInput(node, context.ctx, modifier)
}

/** The installed Material canonical tier. */
object MaterialComponents {
    /** Every component, keyed by node type; exactly the advertised app profile. */
    internal val registry: ComposeComponentRegistry<MaterialNodeRenderContext> = ComposeComponentRegistry(
        listOf(
            MaterialTextComponent,
            MaterialRichTextComponent,
            MaterialIconComponent,
            MaterialBadgeComponent,
            MaterialTooltipComponent,
            MaterialImageComponent,
            MaterialSectionHeaderComponent,
            MaterialEmptyStateComponent,
            MaterialProgressComponent,
            MaterialDateStampComponent,
            MaterialRowComponent,
            MaterialColumnComponent,
            MaterialFlowRowComponent,
            MaterialBoxComponent,
            MaterialSurfaceComponent,
            MaterialLazyColumnComponent,
            MaterialVariantHostComponent,
            MaterialCardComponent,
            MaterialCollapsibleComponent,
            MaterialTabsComponent,
            MaterialTabSelectorComponent,
            MaterialTableComponent,
            MaterialPaneScaffoldComponent,
            MaterialReorderableListComponent,
            MaterialChartComponent,
            MaterialCanvasComponent,
            MaterialMonthGridComponent,
            MaterialSpacerComponent,
            MaterialDividerComponent,
            MaterialScaffoldComponent,
            MaterialEditorComponent,
            MaterialButtonComponent,
            MaterialIconButtonComponent,
            MaterialSplitButtonComponent,
            MaterialNavigationRailComponent,
            MaterialSearchBarComponent,
            MaterialDropdownComponent,
            MaterialSegmentedButtonComponent,
            MaterialAppBarRowComponent,
            MaterialAppBarColumnComponent,
            MaterialCarouselComponent,
            MaterialFabMenuComponent,
            MaterialButtonGroupComponent,
            MaterialLazyGridComponent,
            MaterialChipComponent,
            MaterialAssistChipComponent,
            MaterialMenuComponent,
            MaterialCheckboxComponent,
            MaterialSwitchComponent,
            MaterialEnumListComponent,
            MaterialSliderComponent,
            MaterialDateButtonComponent,
            MaterialTimeButtonComponent,
            MaterialTextInputComponent,
        ),
    )

    /** The node types the canonical tier owns. */
    val nodeTypes: Set<String> get() = registry.nodeTypes
}
