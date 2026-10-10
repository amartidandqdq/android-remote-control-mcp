package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SemanticRouteToolTest {
    private val treeParser = mockk<AccessibilityTreeParser>()
    private val actionExecutor = mockk<ActionExecutor>()
    private val provider = mockk<AccessibilityServiceProvider>()
    private val nodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
    private val rootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
    private val window = mockk<AccessibilityWindowInfo>(relaxed = true)
    private lateinit var tool: SemanticRouteTool

    private val tree =
        AccessibilityNodeData(
            id = "root",
            bounds = BoundsData(0, 0, 1080, 2400),
            enabled = true,
            visible = true,
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "node-smart",
                        text = "Smart",
                        bounds = BoundsData(100, 200, 400, 300),
                        clickable = true,
                        enabled = true,
                        visible = true,
                    ),
                    AccessibilityNodeData(
                        id = "node-automation",
                        text = "Automation",
                        bounds = BoundsData(100, 400, 500, 500),
                        enabled = true,
                        visible = true,
                    ),
                ),
        )

    @BeforeEach
    fun setUp() {
        every { provider.isReady() } returns true
        every { provider.clearFrameworkNodeCache() } returns Unit
        every { provider.getAccessibilityWindows() } returns listOf(window)
        every { provider.getCurrentPackageName() } returns "com.tuya.smart"
        every { provider.getCurrentActivityName() } returns ".MainActivity"
        every { window.id } returns 1
        every { window.root } returns rootNode
        every { window.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { window.title } returns "Tuya"
        every { window.layer } returns 0
        every { window.isFocused } returns true
        every { rootNode.refresh() } returns true
        every { rootNode.packageName } returns "com.tuya.smart"
        every { treeParser.parseTree(rootNode, "root_w1", any()) } returns tree
        tool = SemanticRouteTool(treeParser, ElementFinder(), actionExecutor, provider, nodeCache)
    }

    @Test
    fun `clicks named nodes and verifies final screen`() =
        runTest {
            coEvery { actionExecutor.clickNode("node-smart", any()) } returns Result.success(Unit)
            val result =
                tool.execute(
                    buildJsonObject {
                        put("expected_package", "com.tuya.smart")
                        put(
                            "steps",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("action", "click")
                                        put("value", "Smart")
                                    },
                                )
                            },
                        )
                        put("final_assert", buildJsonObject { put("value", "Automation") })
                    },
                )

            val text = (result.content.single() as TextContent).text
            assertTrue(text.contains("executed=1"))
            assertTrue(text.contains("final_assert=true"))
            coVerify(exactly = 1) { actionExecutor.clickNode("node-smart", any()) }
        }

    @Test
    fun `skips a completed step when its next-screen fingerprint is present`() =
        runTest {
            val result =
                tool.execute(
                    buildJsonObject {
                        put("expected_package", "com.tuya.smart")
                        put(
                            "steps",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("action", "click")
                                        put("value", "Smart")
                                        put("skip_if", buildJsonObject { put("value", "Automation") })
                                    },
                                )
                            },
                        )
                    },
                )

            assertTrue((result.content.single() as TextContent).text.contains("skipped=1"))
            coVerify(exactly = 0) { actionExecutor.clickNode(any(), any()) }
        }

    @Test
    fun `resumes after the deepest visible wait marker`() =
        runTest {
            coEvery { actionExecutor.tap(300f, 450f) } returns Result.success(Unit)

            val result =
                tool.execute(
                    buildJsonObject {
                        put("expected_package", "com.tuya.smart")
                        put(
                            "steps",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("action", "click")
                                        put("value", "Smart")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("action", "wait")
                                        put("value", "Automation")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("action", "tap")
                                        put("value", "Automation")
                                    },
                                )
                            },
                        )
                    },
                )

            val text = (result.content.single() as TextContent).text
            assertTrue(text.contains("executed=1"))
            assertTrue(text.contains("skipped=2"))
            coVerify(exactly = 0) { actionExecutor.clickNode("node-smart", any()) }
            coVerify(exactly = 1) { actionExecutor.tap(300f, 450f) }
        }

    @Test
    fun `stops before action when package guard fails`() =
        runTest {
            every { provider.getCurrentPackageName() } returns "com.example.other"

            val error =
                assertThrows<McpToolException.ActionFailed> {
                    tool.execute(
                        buildJsonObject {
                            put("expected_package", "com.tuya.smart")
                            put(
                                "steps",
                                buildJsonArray {
                                    add(buildJsonObject { put("action", "back") })
                                },
                            )
                        },
                    )
                }

            assertTrue(error.message!!.contains("expected package"))
            coVerify(exactly = 0) { actionExecutor.pressBack() }
        }
}
