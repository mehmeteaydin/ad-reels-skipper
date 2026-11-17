package com.adreels.skipper.service

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.adreels.skipper.domain.SkipperConfig
import com.adreels.skipper.domain.VisibleNode
import com.adreels.skipper.domain.WindowSnapshot

/**
 * Extracts a bounded, visibility-filtered [WindowSnapshot] from an Android accessibility tree
 * without logging or persisting inspected screen text.
 */
class AccessibilityTreeExtractor(
    private val maxVisibleNodes: Int = SkipperConfig.DEFAULT_MAX_VISIBLE_NODES,
) {
    /** Traverses [root] iteratively, collecting up to `maxVisibleNodes` nodes visible to the user. */
    fun extract(packageName: String?, root: AccessibilityNodeInfo?): WindowSnapshot {
        if (root == null || maxVisibleNodes <= 0) {
            return WindowSnapshot(packageName = packageName, nodes = emptyList())
        }

        val visibleNodes = ArrayList<VisibleNode>(minOf(maxVisibleNodes, INITIAL_CAPACITY))
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visitedCount = 0

        try {
            while (queue.isNotEmpty() && visibleNodes.size < maxVisibleNodes && visitedCount < MAX_TOTAL_VISITED) {
                val current = queue.removeFirst()
                visitedCount++
                try {
                    if (current.isVisibleToUser) {
                        visibleNodes.add(
                            VisibleNode(
                                text = current.text?.toString(),
                                contentDescription = current.contentDescription?.toString(),
                                isVisibleToUser = true,
                                isSelected = current.isSelected,
                                isClickable = current.isClickable,
                            ),
                        )
                    }
                    if (visibleNodes.size < maxVisibleNodes) {
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
        const val MAX_TOTAL_VISITED = 2000
    }
}
