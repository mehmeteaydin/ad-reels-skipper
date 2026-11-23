package com.adreels.skipper.service

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.adreels.skipper.domain.ReelAdDetector
import com.adreels.skipper.domain.SkipperConfig
import com.adreels.skipper.domain.VisibleNode
import com.adreels.skipper.domain.WindowSnapshot

/**
 * Extracts a bounded, visibility-filtered [WindowSnapshot] from an Android accessibility tree
 * without consuming [maxVisibleNodes] on unlabeled layout containers and with early-exit termination
 * once Reels context and an active ad keyword match are confirmed, without logging or persisting screen text.
 */
class AccessibilityTreeExtractor(
    private val maxVisibleNodes: Int = SkipperConfig.DEFAULT_MAX_VISIBLE_NODES,
) {
    /**
     * Traverses [root] iteratively, collecting up to `maxVisibleNodes` visible labeled nodes
     * (skipping unlabeled layout containers from the visible budget) and stopping early as soon as
     * both active Reels context and any keyword in [keywords] match.
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
                    val visibleNode = toVisibleLabeledNode(current)
                    if (visibleNode != null) {
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
                        enqueueChildren(current, queue)
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

    private fun toVisibleLabeledNode(node: AccessibilityNodeInfo): VisibleNode? {
        if (!node.isVisibleToUser) return null
        val rawText = node.text?.toString()
        val rawDesc = node.contentDescription?.toString()
        if (rawText.isNullOrBlank() && rawDesc.isNullOrBlank()) return null
        return VisibleNode(
            text = rawText,
            contentDescription = rawDesc,
            isVisibleToUser = true,
            isSelected = node.isSelected,
            isClickable = node.isClickable,
        )
    }

    private fun enqueueChildren(
        node: AccessibilityNodeInfo,
        queue: ArrayDeque<AccessibilityNodeInfo>,
    ) {
        val childCount = node.childCount
        for (i in 0 until childCount) {
            node.getChild(i)?.let(queue::addLast)
        }
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
        const val MAX_TOTAL_VISITED = 2000
    }
}
