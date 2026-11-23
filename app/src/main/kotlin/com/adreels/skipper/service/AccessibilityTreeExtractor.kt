package com.adreels.skipper.service

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.adreels.skipper.domain.ReelAdDetector
import com.adreels.skipper.domain.SkipperConfig
import com.adreels.skipper.domain.VisibleNode
import com.adreels.skipper.domain.WindowSnapshot

/**
 * Extracts a bounded, visibility-filtered [WindowSnapshot] from an Android accessibility tree
 * with early-exit termination once Reels context and an active ad keyword match are confirmed,
 * without logging or persisting inspected screen text.
 */
class AccessibilityTreeExtractor(
    private val maxVisibleNodes: Int = SkipperConfig.DEFAULT_MAX_VISIBLE_NODES,
) {
    /**
     * Traverses [root] iteratively, collecting up to `maxVisibleNodes` visible nodes and stopping
     * early as soon as both active Reels context and any keyword in [keywords] match.
     */
    fun extract(
        packageName: String?,
        root: AccessibilityNodeInfo?,
        keywords: Set<String> = SkipperConfig.DEFAULT_KEYWORDS,
    ): WindowSnapshot {
        if (root == null || maxVisibleNodes <= 0) {
            return WindowSnapshot(packageName = packageName, nodes = emptyList())
        }

        val normalizedKeywords = ReelAdDetector.normalizeKeywords(keywords)
        val visibleNodes = ArrayList<VisibleNode>(minOf(maxVisibleNodes, INITIAL_CAPACITY))
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visitedCount = 0
        var hasReelsContext = false
        var hasSponsoredLabel = false

        try {
            while (
                queue.isNotEmpty() &&
                visibleNodes.size < maxVisibleNodes &&
                visitedCount < MAX_TOTAL_VISITED
            ) {
                val current = queue.removeFirst()
                visitedCount++
                var matchedBoth = false
                try {
                    if (current.isVisibleToUser) {
                        val visibleNode = VisibleNode(
                            text = current.text?.toString(),
                            contentDescription = current.contentDescription?.toString(),
                            isVisibleToUser = true,
                            isSelected = current.isSelected,
                            isClickable = current.isClickable,
                        )
                        visibleNodes.add(visibleNode)
                        val textTokens = visibleNode.text
                            ?.let(ReelAdDetector.Companion::normalizeTokens)
                            .orEmpty()
                        val descTokens = visibleNode.contentDescription
                            ?.let(ReelAdDetector.Companion::normalizeTokens)
                            .orEmpty()
                        if (!hasReelsContext && ReelAdDetector.isReelsContextNode(visibleNode, textTokens, descTokens)) {
                            hasReelsContext = true
                        }
                        if (!hasSponsoredLabel && ReelAdDetector.isSponsoredNode(textTokens, descTokens, normalizedKeywords)) {
                            hasSponsoredLabel = true
                        }
                        matchedBoth = hasReelsContext && hasSponsoredLabel
                    }
                    if (!matchedBoth && visibleNodes.size < maxVisibleNodes && visitedCount < MAX_TOTAL_VISITED) {
                        val childCount = current.childCount
                        for (i in 0 until childCount) {
                            current.getChild(i)?.let(queue::addLast)
                        }
                    }
                } finally {
                    if (current !== root) {
                        safeRecycle(current)
                    }
                }
                if (matchedBoth) break
            }
        } catch (_: RuntimeException) {
            // Fail-safe traversal: partial snapshot stays bounded and never crashes service.
        } finally {
            while (queue.isNotEmpty()) {
                val leftover = queue.removeFirst()
                if (leftover !== root) {
                    safeRecycle(leftover)
                }
            }
        }

        return WindowSnapshot(packageName = packageName, nodes = visibleNodes)
    }

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                node.recycle()
            } catch (_: IllegalStateException) {
                // Node already recycled or backed by a unit-test stub.
            }
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val MAX_TOTAL_VISITED = 400
    }
}
