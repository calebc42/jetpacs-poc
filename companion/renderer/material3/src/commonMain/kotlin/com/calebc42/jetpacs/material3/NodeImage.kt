// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Where `image` nodes fetch from: the shared [ImageCache] in production, a
 * stub in headless tests so no socket is opened. The only seam the Material
 * renderer exposes for the network.
 */
val LocalMaterialImageSource =
    staticCompositionLocalOf<suspend (String, ImageLoader.Limits) -> ImageBitmap?> {
        ImageCache::get
    }

/**
 * The decoded image for an `image` node, or null while it loads and on any
 * failure, in which case the caller shows its neutral placeholder.
 */
@Composable
internal fun rememberNodeImage(url: String): ImageBitmap? {
    val limits = ImageLoader.DEFAULT_LIMITS
    val source = LocalMaterialImageSource.current
    // LD-11: through the cache, so scrolling a lazy_column back to a seen
    // image does not re-fetch, and N nodes on one URL share one load.
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = source(url, limits)
    }
    return bitmap
}
