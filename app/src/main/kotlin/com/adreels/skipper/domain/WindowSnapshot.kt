package com.adreels.skipper.domain

/**
 * Lightweight, platform-free snapshot of a UI node from the active foreground window.
 */
data class VisibleNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val isVisibleToUser: Boolean = true,
    val isSelected: Boolean = false,
    val isClickable: Boolean = false,
)

/**
 * Immutable foreground window snapshot passed from the accessibility service to the domain layer.
 */
data class WindowSnapshot(
    val packageName: String?,
    val nodes: List<VisibleNode>,
)
