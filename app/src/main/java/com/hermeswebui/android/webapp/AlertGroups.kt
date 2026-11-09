package com.hermeswebui.android.webapp

/** A gateway alert the tray shows, reduced to what grouping needs. */
data class ShownAlert(val id: Int, val group: String, val title: String, val channelId: String, val postedAt: Long)

/** A per-session summary; its lines are the newest child titles, already privacy-filtered. */
data class GroupSummary(val group: String, val sessionId: String, val count: Int, val lines: List<String>, val channelId: String)

data class GroupPlan(val post: List<GroupSummary>, val cancel: List<String>)

/**
 * Per-session grouping of gateway alerts. A group gets a summary once it shows two alerts. A summary
 * is withdrawn only when its group is empty, because cancelling a summary also cancels its children;
 * Android shows a group with one child as that child.
 */
object AlertGroups {
    private const val PREFIX = "hermes.webapp:"
    private const val MAX_LINES = 5
    private const val SUMMARY_ID_BASE = 0x7F shl 24

    fun key(sessionId: String): String = PREFIX + sessionId

    /** Summary notification id, outside every [GatewayAlertKind] range so request codes never collide. */
    fun summaryId(group: String): Int = SUMMARY_ID_BASE + (group.hashCode() and 0x00FF_FFFF)

    /** [touched] limits re-posting to groups whose alerts just changed; any summarized group left empty is cancelled. */
    fun plan(children: Collection<ShownAlert>, summarized: Set<String>, touched: Set<String>): GroupPlan {
        val byGroup = children.filter { it.group.startsWith(PREFIX) }.groupBy { it.group }
        val post = touched.mapNotNull { group ->
            val shown = byGroup[group]?.sortedByDescending { it.postedAt }?.takeIf { it.size >= 2 } ?: return@mapNotNull null
            GroupSummary(
                group = group,
                sessionId = group.removePrefix(PREFIX),
                count = shown.size,
                lines = shown.take(MAX_LINES).map { it.title },
                channelId = shown.first().channelId,
            )
        }
        val cancel = summarized.filter { byGroup[it].isNullOrEmpty() }
        return GroupPlan(post, cancel)
    }
}
