package com.voyagerfiles.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voyagerfiles.viewmodel.BrowserNavigationBounds

/** One tappable part of the path bar. [outside] parts lie above a pinned tab's folder. */
internal data class Crumb(val label: String, val path: String, val outside: Boolean = false)

/**
 * The parts of [path] to show. Below a session root ([rootPath] other than "/"), the root shows as
 * [rootLabel] (e.g. "Internal storage") and nothing above it is offered; otherwise the bar starts
 * at "/".
 */
internal fun breadcrumbs(
    path: String,
    rootPath: String? = null,
    rootLabel: String? = null,
    limitPath: String? = null,
): List<Crumb> {
    val root = rootPath
        ?.takeUnless { it.contains("://") }
        ?.let(BrowserNavigationBounds::normalizePath)
        ?.takeIf { it != "/" && BrowserNavigationBounds.isPathAtOrInsideRoot(path, it) }
    val base = root ?: "/"
    val first = Crumb(if (root != null) rootLabel ?: root.substringAfterLast('/') else "/", base)
    val relative = BrowserNavigationBounds.normalizePath(path).removePrefix(base).split("/").filter { it.isNotEmpty() }
    val crumbs = listOf(first) + relative.mapIndexed { index, part ->
        val prefix = if (base == "/") "" else base
        Crumb(part, prefix + "/" + relative.subList(0, index + 1).joinToString("/"))
    }
    val limit = limitPath?.takeUnless { it.contains("://") }?.let(BrowserNavigationBounds::normalizePath)
        ?: return crumbs
    return crumbs.map { crumb ->
        crumb.copy(outside = !BrowserNavigationBounds.isPathAtOrInsideRoot(crumb.path, limit))
    }
}

@Composable
fun PathBreadcrumb(
    path: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    rootPath: String? = null,
    rootLabel: String? = null,
    /** A pinned tab's folder: parts above it are dimmed, and tapping one opens it in another tab. */
    limitPath: String? = null,
) {
    val scrollState = rememberScrollState()
    val crumbs = breadcrumbs(path, rootPath, rootLabel, limitPath)

    LaunchedEffect(path) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val isLast = index == crumbs.lastIndex && index > 0
            Text(
                text = crumb.label,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    isLast -> MaterialTheme.colorScheme.onSurface
                    crumb.outside -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .clickable(enabled = !isLast) { onNavigate(crumb.path) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}
