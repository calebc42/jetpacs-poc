// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.glance

import android.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontStyle
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.calebc42.ebp.renderer.model.NodeComponentRegistry
import com.calebc42.ebp.renderer.model.RendererContribution
import com.calebc42.ebp.renderer.model.RendererMemberSupport
import com.calebc42.ebp.renderer.model.RendererProfile
import com.calebc42.ebp.renderer.model.RendererRegistry
import com.calebc42.ebp.renderer.model.RendererTargetLimits
import com.calebc42.ebp.renderer.model.vocabulary.NodeMemberSet
import com.calebc42.ebp.renderer.model.vocabulary.generated.BadgeMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.BoxMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.ButtonMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.CardMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.ColumnMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.DividerMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.EmptyStateMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.IconButtonMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.IconMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.LazyColumnMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.RowMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.SectionHeaderMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.SpacerMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.SurfaceMembers
import com.calebc42.ebp.renderer.model.vocabulary.generated.TextMembers
import com.calebc42.ebp.wire.MAX_WIDGET_LAZY_ITEMS
import com.calebc42.ebp.wire.MAX_WIDGET_NODE_DEPTH
import com.calebc42.ebp.wire.MAX_WIDGET_NODES
import com.calebc42.ebp.wire.MAX_WIDGET_REMOTE_VIEWS_BYTES
import com.calebc42.ebp.wire.MAX_WIDGET_SIZE_VARIANTS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Converts an admitted descriptor to an opaque Android click action. */
fun interface GlanceActionResolver {
    fun resolve(path: String, descriptor: JsonObject): Action?
}

/** A widget component bound to the generated member set of the contract node it presents. */
internal abstract class GlanceComponent(
    final override val nodeType: String,
    final override val members: NodeMemberSet,
) : GlanceNodeComponent

/**
 * Every widget node type the Glance translation presents, keyed by type.
 * [GlanceRendererContribution] advertises exactly these, so the advertised
 * and the implemented widget profile cannot split.
 */
val GlanceComponents: NodeComponentRegistry<GlanceNodeComponent> = NodeComponentRegistry(
    listOf(
        GlanceTextComponent,
        GlanceIconComponent,
        GlanceRowComponent,
        GlanceColumnComponent,
        GlanceBoxComponent,
        GlanceSurfaceComponent,
        GlanceLazyColumnComponent,
        GlanceSpacerComponent,
        GlanceDividerComponent,
        GlanceCardComponent,
        GlanceButtonComponent,
        GlanceIconButtonComponent,
        GlanceBadgeComponent,
        GlanceSectionHeaderComponent,
        GlanceEmptyStateComponent,
    ),
)

/** The exact EBP surface implemented by the Glance translation below. */
val GlanceRendererContribution = RendererContribution(
    id = "glance.widget",
    nodeTypes = GlanceComponents.nodeTypes,
    builtins = setOf("surface.open"),
    memberSupport = RendererMemberSupport(
        universal = setOf("key", "id", "semantics"),
        nodes = mapOf(
            "text" to setOf("text", "style", "font_weight", "color", "max_lines"),
            "icon" to setOf("name", "size", "color", "content_description"),
            "row" to setOf("children", "spacing", "align", "fill"),
            "column" to setOf("children", "spacing", "align", "fill"),
            "box" to setOf("children", "alignment", "on_tap"),
            "surface" to setOf("children", "color"),
            "lazy_column" to setOf("children", "spacing", "content_padding"),
            "spacer" to emptySet(),
            "divider" to setOf("color", "thickness"),
            "card" to setOf("children", "on_tap"),
            "button" to setOf("label", "on_tap", "enabled"),
            "icon_button" to setOf(
                "icon", "on_tap", "content_description", "enabled", "size", "color",
            ),
            "badge" to setOf("label", "icon", "color", "children"),
            "section_header" to setOf("title", "trailing"),
            "empty_state" to setOf("icon", "title", "caption", "action_label", "on_tap"),
        ),
        semantics = setOf("name", "description"),
        surface = setOf("title", "body", "empty", "header_action", "size_variants"),
    ),
    limits = RendererTargetLimits(
        maxNodes = MAX_WIDGET_NODES,
        maxLazyItems = MAX_WIDGET_LAZY_ITEMS,
        maxNodeDepth = MAX_WIDGET_NODE_DEPTH,
        maxSizeVariants = MAX_WIDGET_SIZE_VARIANTS,
        maxRemoteViewsBytes = MAX_WIDGET_REMOTE_VIEWS_BYTES,
    ),
)

val GlanceWidgetProfile: RendererProfile =
    RendererRegistry(listOf(GlanceRendererContribution))
        .profile(GlanceRendererContribution.id)

/** Render one validated widget wrapper directly from the neutral JSON IR. */
@Composable
fun RenderGlanceWidget(
    spec: JsonObject,
    widthDp: Float,
    heightDp: Float,
    actions: GlanceActionResolver,
    stale: Boolean = false,
    modifier: GlanceModifier = GlanceModifier,
) {
    val body = selectWidgetBody(spec, widthDp, heightDp) ?: return
    Column(
        modifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(24.dp)
            .padding(14.dp),
    ) {
        val headerAction = spec["header_action"] as? JsonObject
        val headerModifier = headerAction
            ?.let { actions.resolve("header", it) }
            ?.let { GlanceModifier.fillMaxWidth().clickable(it) }
            ?: GlanceModifier.fillMaxWidth()
        Row(headerModifier, verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                text = spec.string("title"),
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                ),
                maxLines = 1,
            )
            if (stale) {
                Text(
                    text = "Offline",
                    style = TextStyle(
                        color = GlanceTheme.colors.error,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                )
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        RenderGlanceNode(body, GlanceRenderContext(actions, "body"), GlanceModifier.fillMaxSize())
    }
}

/** Present one admitted widget [node] at [path] for [actions]; an unowned type presents nothing. */
@Composable
fun RenderGlanceNode(
    node: JsonObject,
    actions: GlanceActionResolver,
    path: String,
    modifier: GlanceModifier = GlanceModifier,
) = RenderGlanceNode(node, GlanceRenderContext(actions, path), modifier)

/** Present one admitted widget [node] in [context] through [GlanceComponents]. */
@Composable
fun RenderGlanceNode(
    node: JsonObject,
    context: GlanceRenderContext,
    modifier: GlanceModifier = GlanceModifier,
) {
    GlanceComponents.componentFor(node.string("t"))
        ?.render(node, context, modifier.withSemantics(node))
}

internal object GlanceTextComponent : GlanceComponent("text", TextMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) = Text(
        text = node.string("text"),
        modifier = modifier,
        style = textStyle(node),
        maxLines = node.integer("max_lines") ?: Int.MAX_VALUE,
    )
}

internal object GlanceIconComponent : GlanceComponent("icon", IconMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) = RenderIcon(
        name = node.string("name"),
        contentDescription = node.string("content_description").ifBlank {
            node.semanticDescription()
        },
        color = node.string("color"),
        size = node.number("size").takeIf { it > 0f } ?: 24f,
        modifier = modifier,
    )
}

internal object GlanceRowComponent : GlanceComponent("row", RowMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) = Row(
        modifier = if (node.boolean("fill")) modifier.fillMaxWidth() else modifier,
        verticalAlignment = verticalAlignment(node.string("align")),
    ) {
        RenderHorizontalChildren(node.children(), context, node.number("spacing"))
    }
}

internal object GlanceColumnComponent : GlanceComponent("column", ColumnMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) = Column(
        modifier = if (node.boolean("fill")) modifier.fillMaxWidth() else modifier,
        horizontalAlignment = horizontalAlignment(node.string("align")),
    ) {
        RenderVerticalChildren(node.children(), context, node.number("spacing"))
    }
}

internal object GlanceBoxComponent : GlanceComponent("box", BoxMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) = Box(
        modifier = modifier.withAction(node, "on_tap", context),
        contentAlignment = boxAlignment(node.string("alignment")),
    ) {
        node.children().forEachIndexed { index, child ->
            RenderGlanceNode(child, context.child(index))
        }
    }
}

internal object GlanceSurfaceComponent : GlanceComponent("surface", SurfaceMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) = Column(
        modifier.background(color(node.string("color"), GlanceTheme.colors.surface)),
    ) {
        RenderVerticalChildren(node.children(), context, 0f)
    }
}

internal object GlanceLazyColumnComponent : GlanceComponent("lazy_column", LazyColumnMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) = LazyColumn(
        modifier = modifier.padding(node.number("content_padding").dp),
    ) {
        val spacing = node.number("spacing")
        val children = node.children()
        children.forEachIndexed { index, child ->
            item {
                Column {
                    RenderGlanceNode(child, context.child(index))
                    if (spacing > 0f && index != children.lastIndex) {
                        Spacer(GlanceModifier.height(spacing.dp))
                    }
                }
            }
        }
    }
}

internal object GlanceSpacerComponent : GlanceComponent("spacer", SpacerMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) =
        Spacer(modifier.size(8.dp))
}

internal object GlanceDividerComponent : GlanceComponent("divider", DividerMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) = Spacer(
        modifier.fillMaxWidth()
            .height((node.number("thickness").takeIf { it > 0f } ?: 1f).dp)
            .background(color(node.string("color"), GlanceTheme.colors.outline)),
    )
}

internal object GlanceCardComponent : GlanceComponent("card", CardMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) = Column(
        modifier.fillMaxWidth()
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(16.dp)
            .withAction(node, "on_tap", context)
            .padding(12.dp),
    ) {
        RenderVerticalChildren(node.children(), context, 0f)
    }
}

internal object GlanceButtonComponent : GlanceComponent("button", ButtonMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) =
        RenderButton(node, context, modifier)
}

internal object GlanceIconButtonComponent : GlanceComponent("icon_button", IconButtonMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) {
        val clickable = context.resolve(node["on_tap"] as? JsonObject)
        RenderIcon(
            name = node.string("icon"),
            contentDescription = node.string("content_description").ifBlank {
                node.semanticDescription()
            },
            color = node.string("color"),
            size = node.number("size").takeIf { it > 0f } ?: 40f,
            modifier = if (node.boolean("enabled", true) && clickable != null) {
                modifier.clickable(clickable)
            } else {
                modifier
            },
        )
    }
}

internal object GlanceBadgeComponent : GlanceComponent("badge", BadgeMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) =
        Row(modifier, verticalAlignment = Alignment.Vertical.CenterVertically) {
            node.string("icon").takeIf(String::isNotBlank)?.let { icon ->
                RenderIcon(icon, null, node.string("color"), 16f)
                Spacer(GlanceModifier.width(4.dp))
            }
            Text(
                node.string("label"),
                style = TextStyle(
                    color = color(node.string("color"), GlanceTheme.colors.onSurfaceVariant),
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
            )
            node.children().forEachIndexed { index, child ->
                RenderGlanceNode(child, context.child(index))
            }
        }
}

internal object GlanceSectionHeaderComponent : GlanceComponent("section_header", SectionHeaderMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) = Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            node.string("title"),
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            ),
            maxLines = 1,
        )
        (node["trailing"] as? JsonObject)?.let {
            RenderGlanceNode(it, context.child("trailing"))
        }
    }
}

internal object GlanceEmptyStateComponent : GlanceComponent("empty_state", EmptyStateMembers) {
    @Composable
    override fun render(node: JsonObject, context: GlanceRenderContext, modifier: GlanceModifier) =
        RenderEmptyState(node, context, modifier)
}

@Composable
private fun RenderButton(
    node: JsonObject,
    context: GlanceRenderContext,
    modifier: GlanceModifier,
) {
    val action = context.resolve(node["on_tap"] as? JsonObject)
    if (action == null) {
        Text(node.string("label"), modifier = modifier, style = textStyle(node), maxLines = 1)
    } else {
        Button(
            text = node.string("label"),
            onClick = action,
            modifier = modifier,
            enabled = node.boolean("enabled", true),
            colors = ButtonDefaults.buttonColors(),
            maxLines = 1,
        )
    }
}

@Composable
private fun RenderEmptyState(
    node: JsonObject,
    context: GlanceRenderContext,
    modifier: GlanceModifier,
) {
    Column(
        modifier.fillMaxWidth().padding(12.dp),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        node.string("icon").takeIf(String::isNotBlank)?.let {
            RenderIcon(it, null, "", 32f)
            Spacer(GlanceModifier.height(6.dp))
        }
        node.string("title").takeIf(String::isNotBlank)?.let {
            Text(it, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp))
        }
        node.string("caption").takeIf(String::isNotBlank)?.let {
            Text(it, style = TextStyle(fontSize = 12.sp), maxLines = 3)
        }
        val label = node.string("action_label")
        val action = context.resolve(node["on_tap"] as? JsonObject)
        if (label.isNotBlank() && action != null) {
            Spacer(GlanceModifier.height(8.dp))
            Button(label, action)
        }
    }
}

@Composable
private fun RenderIcon(
    name: String,
    contentDescription: String?,
    color: String,
    size: Float,
    modifier: GlanceModifier = GlanceModifier,
) {
    Image(
        provider = ImageProvider(iconResource(name)),
        contentDescription = contentDescription,
        modifier = modifier.size(size.dp),
        colorFilter = androidx.glance.ColorFilter.tint(
            color(color, GlanceTheme.colors.onSurface),
        ),
    )
}

@Composable
private fun androidx.glance.layout.RowScope.RenderHorizontalChildren(
    children: List<JsonObject>,
    context: GlanceRenderContext,
    spacing: Float,
) {
    val head = children.take(9)
    head.forEachIndexed { index, child ->
        RenderGlanceNode(child, context.child(index))
        if (spacing > 0f && (index != head.lastIndex || children.size > 9)) {
            Spacer(GlanceModifier.width(spacing.dp))
        }
    }
    if (children.size > 9) {
        Row { RenderHorizontalChildren(children.drop(9), context.child("9+"), spacing) }
    }
}

@Composable
private fun androidx.glance.layout.ColumnScope.RenderVerticalChildren(
    children: List<JsonObject>,
    context: GlanceRenderContext,
    spacing: Float,
) {
    val head = children.take(9)
    head.forEachIndexed { index, child ->
        RenderGlanceNode(child, context.child(index))
        if (spacing > 0f && (index != head.lastIndex || children.size > 9)) {
            Spacer(GlanceModifier.height(spacing.dp))
        }
    }
    if (children.size > 9) {
        Column { RenderVerticalChildren(children.drop(9), context.child("9+"), spacing) }
    }
}

private fun GlanceModifier.withAction(
    node: JsonObject,
    member: String,
    context: GlanceRenderContext,
): GlanceModifier = context.resolve(node[member] as? JsonObject)
    ?.let(::clickable)
    ?: this

private fun GlanceModifier.withSemantics(node: JsonObject): GlanceModifier {
    val description = node.semanticDescription()
    return if (description.isBlank()) this else semantics { contentDescription = description }
}

private fun JsonObject.semanticDescription(): String {
    val semantics = this["semantics"] as? JsonObject
    return listOf(semantics?.string("name"), semantics?.string("description"))
        .filterNotNull()
        .filter(String::isNotBlank)
        .joinToString(". ")
}

private fun JsonObject.children(): List<JsonObject> =
    (this["children"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun JsonObject.integer(member: String): Int? =
    (this[member] as? JsonPrimitive)?.content?.toDoubleOrNull()
        ?.takeIf { it % 1.0 == 0.0 }
        ?.toInt()

@Composable
private fun textStyle(node: JsonObject): TextStyle {
    val styleName = node.string("style")
    val authoredWeight = node.string("font_weight")
    val weight = when {
        authoredWeight == "bold" || authoredWeight.toIntOrNull()?.let { it >= 600 } == true ->
            FontWeight.Bold
        authoredWeight == "medium" -> FontWeight.Medium
        else -> null
    }
    return TextStyle(
        color = color(node.string("color"), GlanceTheme.colors.onSurface),
        fontWeight = weight,
        fontStyle = FontStyle.Normal,
        fontSize = when (styleName) {
            "display_large", "headline_large" -> 24.sp
            "display_medium", "headline_medium" -> 20.sp
            "display_small", "headline_small", "title_large" -> 18.sp
            "title_medium" -> 16.sp
            "title_small", "label_large" -> 14.sp
            "label_medium", "body_small" -> 11.sp
            "label_small" -> 10.sp
            else -> 13.sp
        },
    )
}

@Composable
private fun color(raw: String, fallback: ColorProvider): ColorProvider {
    val parsed = when (raw.length) {
        7 -> raw.takeIf { it.startsWith('#') }?.drop(1)?.toLongOrNull(16)
            ?.let { Color(0xFF000000 or it) }
        9 -> raw.takeIf { it.startsWith('#') }?.drop(1)?.toLongOrNull(16)?.let(::Color)
        else -> null
    }
    return parsed?.let(::ColorProvider) ?: when (raw) {
        "primary" -> GlanceTheme.colors.primary
        "secondary" -> GlanceTheme.colors.secondary
        "error" -> GlanceTheme.colors.error
        "surface" -> GlanceTheme.colors.surface
        "on_surface" -> GlanceTheme.colors.onSurface
        else -> fallback
    }
}

private fun iconResource(name: String): Int = when (name) {
    "add", "create", "new" -> R.drawable.ic_input_add
    "done", "check", "check_circle" -> R.drawable.checkbox_on_background
    "delete", "trash" -> R.drawable.ic_menu_delete
    "edit" -> R.drawable.ic_menu_edit
    "refresh" -> R.drawable.ic_popup_sync
    "schedule", "alarm" -> R.drawable.ic_lock_idle_alarm
    "search" -> R.drawable.ic_menu_search
    "share" -> R.drawable.ic_menu_share
    "info" -> R.drawable.ic_menu_info_details
    else -> R.drawable.ic_menu_more
}

private fun verticalAlignment(raw: String): Alignment.Vertical = when (raw) {
    "center" -> Alignment.Vertical.CenterVertically
    "end", "bottom" -> Alignment.Vertical.Bottom
    else -> Alignment.Vertical.Top
}

private fun horizontalAlignment(raw: String): Alignment.Horizontal = when (raw) {
    "center" -> Alignment.Horizontal.CenterHorizontally
    "end" -> Alignment.Horizontal.End
    else -> Alignment.Horizontal.Start
}

private fun boxAlignment(raw: String): Alignment = when (raw) {
    "top_start" -> Alignment.TopStart
    "top_center" -> Alignment.TopCenter
    "top_end" -> Alignment.TopEnd
    "center_start" -> Alignment.CenterStart
    "center", "center_center" -> Alignment.Center
    "center_end" -> Alignment.CenterEnd
    "bottom_start" -> Alignment.BottomStart
    "bottom_center" -> Alignment.BottomCenter
    "bottom_end" -> Alignment.BottomEnd
    else -> Alignment.TopStart
}
