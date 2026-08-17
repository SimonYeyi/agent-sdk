package io.github.yeyi.agent.demo.team.ui

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.material3.DrawerValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for demos/team demo drawer auto-open bug.
 *
 * Root cause: ModalNavigationDrawer recomputes AnchoredDraggableState anchors
 * based on drawerContent's measured width during layout (NavigationDrawer.kt:419-430).
 * When drawerContent conditionally renders nothing (width=0), the new anchors
 * collapse to {Closed: 0, Open: 0}, snapping offset to 0 and eventually flipping
 * state to Open on the next toggle.
 *
 * The fix keeps drawerContent's outer wrapper stable (ModalDrawerSheet always
 * rendered with fixed width) and only conditionally renders the inner content.
 * This test models the buggy path and the fixed path and asserts that:
 * - Buggy path (width collapses): drawer becomes visible / state flips to Open
 * - Fixed path (width stable): drawer stays Closed throughout
 */
@OptIn(ExperimentalFoundationApi::class)
class DemoDrawerAnchorCollapseTest {

    private fun newState(initial: DrawerValue) = AnchoredDraggableState(
        initialValue = initial,
        positionalThreshold = { distance -> distance * 0.5f },
        velocityThreshold = { 400f },
        snapAnimationSpec = snap(),
        decayAnimationSpec = exponentialDecay(),
    )

    private fun normalAnchors() = DraggableAnchors<DrawerValue> {
        DrawerValue.Closed at -300f
        DrawerValue.Open at 0f
    }

    private fun collapsedAnchors() = DraggableAnchors<DrawerValue> {
        DrawerValue.Closed at 0f
        DrawerValue.Open at 0f
    }

    /**
     * 模拟当前 buggy 代码: drawerContent 条件渲染,voiceMode 切换时 width 变化。
     * 期望: 暴露 bug — offset 跳到 0,state 最终翻到 Open。
     */
    @Test
    fun `buggy conditional drawerContent flips drawer to Open after round-trip`() {
        val state = newState(DrawerValue.Closed)
        state.updateAnchors(normalAnchors())

        // voiceMode false→true: drawerContent width 300→0
        state.updateAnchors(collapsedAnchors())

        // voiceMode true→false: drawerContent width 0→300
        state.updateAnchors(normalAnchors())

        // Bug manifests — these assertions document the broken behavior
        assertEquals(
            "BUG: voiceMode round-trip leaves drawer Open instead of returning to Closed",
            DrawerValue.Open,
            state.currentValue
        )
    }

    /**
     * 模拟修复后代码: drawerContent 始终保持 ModalDrawerSheet,
     * voiceMode 切换不影响外层宽度,anchors 不变。
     * 期望: drawer 始终 Closed,offset 始终 -300。
     */
    @Test
    fun `fixed drawerContent with stable width keeps drawer Closed throughout`() {
        val state = newState(DrawerValue.Closed)
        // 修复后: anchors 只初始化一次,后续 voiceMode 切换不触发 updateAnchors
        state.updateAnchors(normalAnchors())

        // 模拟 voiceMode 切换但不调用 updateAnchors (因为 drawerContent width 不变)
        // ... no-op ...

        assertEquals(DrawerValue.Closed, state.currentValue)
        assertEquals(-300f, state.offset, 0.01f)
    }
}