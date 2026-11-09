package com.hermeswebui.android.webapp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlertGroupsTest {
    private val s1 = AlertGroups.key("s1")
    private val s2 = AlertGroups.key("s2")

    private fun shown(id: Int, group: String, title: String, at: Long, channel: String = "c$id") =
        ShownAlert(id, group, "Chat", title, channel, at)

    @Test
    fun `a session with two alerts gets a summary listing the newest first`() {
        val children = listOf(shown(1, s1, "Task finished", 10), shown(2, s1, "Hermes has a question", 20), shown(3, s2, "Task failed", 5))
        val plan = AlertGroups.plan(children, summarized = emptySet(), touched = setOf(s1, s2))
        assertThat(plan.post).containsExactly(GroupSummary(s1, "s1", "Chat", 2, listOf("Hermes has a question", "Task finished"), "c2"))
        assertThat(plan.cancel).isEmpty()
    }

    @Test
    fun `only touched groups are re-posted`() {
        val children = listOf(shown(1, s1, "a", 1), shown(2, s1, "b", 2))
        assertThat(AlertGroups.plan(children, setOf(s1), touched = emptySet()).post).isEmpty()
        assertThat(AlertGroups.plan(children, setOf(s1), touched = setOf(s2)).post).isEmpty()
    }

    @Test
    fun `a summary is withdrawn only once its group is empty`() {
        val one = listOf(shown(1, s1, "a", 1))
        assertThat(AlertGroups.plan(one, setOf(s1), setOf(s1))).isEqualTo(GroupPlan(emptyList(), emptyList()))
        assertThat(AlertGroups.plan(emptyList(), setOf(s1, s2), setOf(s1)).cancel).containsExactly(s1, s2)
    }

    @Test
    fun `summary ids never fall in an alert kind's range`() {
        listOf("s1", "", "x".repeat(300)).map(AlertGroups::key).forEach { group ->
            assertThat(AlertGroups.summaryId(group) ushr 24).isEqualTo(0x7F)
        }
        assertThat(GatewayAlertKind.entries.size).isLessThan(0x7F)
    }

    @Test
    fun `summary lines are bounded and foreign groups are ignored`() {
        val many = (1..8).map { shown(it, s1, "t$it", it.toLong()) } + shown(9, "other", "x", 99) + shown(10, "other", "y", 98)
        val plan = AlertGroups.plan(many, emptySet(), setOf(s1, "other"))
        assertThat(plan.post.single().count).isEqualTo(8)
        assertThat(plan.post.single().lines).containsExactly("t8", "t7", "t6", "t5", "t4").inOrder()
    }
}
