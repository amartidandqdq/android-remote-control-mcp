package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("McpAccessibilityService cache-invalidation wiring")
class McpAccessibilityServiceTest {
    @Nested
    @DisplayName("triggersCacheInvalidation")
    inner class TriggersCacheInvalidation {
        @Test
        @DisplayName("window state changed (rotation / activity transition) triggers invalidation")
        fun `window state changed triggers invalidation`() {
            assertTrue(triggersCacheInvalidation(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        }

        @Test
        @DisplayName("windows changed (keyboard show / hide) triggers invalidation")
        fun `windows changed triggers invalidation`() {
            assertTrue(triggersCacheInvalidation(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        }

        @Test
        @DisplayName("window content changed does NOT trigger invalidation (too frequent)")
        fun `window content changed does not trigger invalidation`() {
            assertFalse(triggersCacheInvalidation(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        }

        @ParameterizedTest
        @ValueSource(
            ints = [
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            ],
        )
        @DisplayName("unrelated event types do NOT trigger invalidation")
        fun `unrelated event types do not trigger invalidation`(eventType: Int) {
            assertFalse(triggersCacheInvalidation(eventType))
        }
    }

    @Nested
    @DisplayName("scheduleCacheInvalidationIfNeeded")
    inner class ScheduleCacheInvalidationIfNeeded {
        @Test
        @DisplayName("schedules the debouncer on a triggering event")
        fun `schedules on a triggering event`() {
            val debouncer = mockk<CacheInvalidationDebouncer>(relaxed = true)

            scheduleCacheInvalidationIfNeeded(AccessibilityEvent.TYPE_WINDOWS_CHANGED, debouncer)

            verify(exactly = 1) { debouncer.schedule() }
        }

        @Test
        @DisplayName("does NOT schedule on a non-triggering event")
        fun `does not schedule on a non-triggering event`() {
            val debouncer = mockk<CacheInvalidationDebouncer>(relaxed = true)

            scheduleCacheInvalidationIfNeeded(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                debouncer,
            )

            verify(exactly = 0) { debouncer.schedule() }
        }

        @Test
        @DisplayName("is a safe no-op when the debouncer is null")
        fun `is a safe no-op when the debouncer is null`() {
            assertDoesNotThrow {
                scheduleCacheInvalidationIfNeeded(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null)
            }
        }
    }

    @Nested
    @DisplayName("invalidateCache")
    inner class InvalidateCache {
        @Test
        @DisplayName("clears the provided cache")
        fun `clears the provided cache`() {
            val cache = mockk<AccessibilityNodeCache>(relaxed = true)

            invalidateCache(cache)

            verify(exactly = 1) { cache.clear() }
        }

        @Test
        @DisplayName("is a safe no-op when the cache is null")
        fun `is a safe no-op when the cache is null`() {
            assertDoesNotThrow { invalidateCache(null) }
        }
    }

    @Nested
    @DisplayName("findFocusedWindowPackageName")
    inner class FindFocusedWindowPackageName {
        @Test
        @DisplayName("returns the focused window package, not the first window of the list")
        fun `returns the focused window package`() {
            val background = window("com.google.android.apps.nexuslauncher", focused = false)
            val foreground = window("com.tuya.smart", focused = true)

            assertEquals(
                "com.tuya.smart",
                findFocusedWindowPackageName(listOf(background, foreground)),
            )
        }

        @Test
        @DisplayName("returns the focused window package even when it precedes the others")
        fun `returns the focused window package when first`() {
            val foreground = window("com.tuya.smart", focused = true)
            val background = window("com.google.android.apps.nexuslauncher", focused = false)

            assertEquals(
                "com.tuya.smart",
                findFocusedWindowPackageName(listOf(foreground, background)),
            )
        }

        @Test
        @DisplayName("returns null when no window is focused")
        fun `returns null when no window is focused`() {
            val first = window("com.tuya.smart", focused = false)
            val second = window("com.android.systemui", focused = false)

            assertNull(findFocusedWindowPackageName(listOf(first, second)))
        }

        @Test
        @DisplayName("returns null when the focused window exposes no root node")
        fun `returns null when the focused window has no root`() {
            val focused = mockk<AccessibilityWindowInfo>()
            every { focused.isFocused } returns true
            every { focused.root } returns null

            assertNull(findFocusedWindowPackageName(listOf(focused)))
        }

        @Test
        @DisplayName("returns null for an empty window list")
        fun `returns null for an empty list`() {
            assertNull(findFocusedWindowPackageName(emptyList()))
        }
    }

    private fun window(
        packageName: String,
        focused: Boolean,
    ): AccessibilityWindowInfo {
        val rootNode = mockk<AccessibilityNodeInfo>()
        every { rootNode.packageName } returns packageName
        val windowInfo = mockk<AccessibilityWindowInfo>()
        every { windowInfo.isFocused } returns focused
        every { windowInfo.root } returns rootNode
        return windowInfo
    }
}
