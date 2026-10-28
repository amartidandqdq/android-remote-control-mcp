package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Executes a bounded sequence of named UI actions in one MCP call.
 *
 * Every step is guarded by [expected_package]. Nodes are reacquired from the live accessibility
 * tree before each action. Optional [skip_if] fingerprints let a route resume from an intermediate
 * screen instead of navigating from the beginning.
 */
class SemanticRouteTool(
    private val treeParser: AccessibilityTreeParser,
    private val elementFinder: ElementFinder,
    private val actionExecutor: ActionExecutor,
    private val accessibilityServiceProvider: AccessibilityServiceProvider,
    private val nodeCache: AccessibilityNodeCache,
) {
    @Suppress("LongMethod", "ThrowsCount")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val expectedPackage = McpToolUtils.requireString(arguments, "expected_package")
        val steps =
            arguments?.get("steps")?.jsonArray
                ?: throw McpToolException.InvalidParams("Missing required parameter 'steps'")
        if (steps.isEmpty() || steps.size > MAX_STEPS) {
            throw McpToolException.InvalidParams("Parameter 'steps' must contain 1..$MAX_STEPS items")
        }

        var executed = 0
        val resumeFrom = findResumeIndex(steps)
        var skipped = resumeFrom
        steps.drop(resumeFrom).forEachIndexed { offset, item ->
            val index = resumeFrom + offset
            val step = item.jsonObject
            guardPackage(expectedPackage, index)
            if (matchesOptional(step["skip_if"] as? JsonObject)) {
                skipped++
                return@forEachIndexed
            }

            when (val action = requiredStepString(step, "action", index).lowercase()) {
                "click", "tap" -> {
                    val snapshot = freshSnapshot()
                    val node = findSingle(step, snapshot, index)
                    val result =
                        if (action == "click" && node.clickable) {
                            actionExecutor.clickNode(node.id, snapshot.windows)
                        } else {
                            actionExecutor.tap(
                                (node.bounds.left + node.bounds.right) / 2f,
                                (node.bounds.top + node.bounds.bottom) / 2f,
                            )
                        }
                    result.getOrElse { cause ->
                        throw McpToolException.ActionFailed("Route step $index '$action' failed: ${cause.message}")
                    }
                }

                "wait" -> {
                    waitForMatch(step, index)
                }

                "assert" -> {
                    val snapshot = freshSnapshot()
                    findSingle(step, snapshot, index)
                }

                "back" -> {
                    actionExecutor.pressBack().getOrElse { cause ->
                        throw McpToolException.ActionFailed("Route step $index 'back' failed: ${cause.message}")
                    }
                }

                "swipe_region" -> {
                    SwipeRegionTool(actionExecutor, accessibilityServiceProvider).execute(step)
                }

                else -> {
                    throw McpToolException.InvalidParams(
                        "Route step $index has unsupported action '$action'",
                    )
                }
            }
            executed++
        }

        guardPackage(expectedPackage, steps.size)
        val finalSnapshot = freshSnapshot()
        val finalCheck = arguments["final_assert"] as? JsonObject
        if (finalCheck != null) findSingle(finalCheck, finalSnapshot, steps.size)
        val fingerprint =
            TreeFingerprint()
                .generate(finalSnapshot.windows)
                .contentHashCode()
                .toUInt()
                .toString(16)

        return McpToolUtils.textResult(
            "Semantic route completed: executed=$executed skipped=$skipped " +
                "package=$expectedPackage activity=${accessibilityServiceProvider.getCurrentActivityName()} " +
                "fingerprint=$fingerprint final_assert=${finalCheck != null}",
        )
    }

    private fun guardPackage(
        expectedPackage: String,
        stepIndex: Int,
    ) {
        val actual = accessibilityServiceProvider.getCurrentPackageName()
        if (actual != expectedPackage) {
            throw McpToolException.ActionFailed(
                "Route stopped before step $stepIndex: expected package '$expectedPackage', found '$actual'",
            )
        }
    }

    @Suppress("MaxLineLength")
    private fun freshSnapshot(): MultiWindowResult = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)

    private fun matchesOptional(criteria: JsonObject?): Boolean {
        if (criteria == null) return false
        val snapshot = freshSnapshot()
        return findMatches(criteria, snapshot).isNotEmpty()
    }

    private fun findResumeIndex(steps: JsonArray): Int {
        for (index in steps.lastIndex downTo 0) {
            val step = steps[index].jsonObject
            if (
                step["action"]?.jsonPrimitive?.contentOrNull == "wait" &&
                findMatches(step, freshSnapshot()).isNotEmpty()
            ) {
                return index + 1
            }
        }
        return 0
    }

    private fun findSingle(
        criteria: JsonObject,
        snapshot: MultiWindowResult,
        stepIndex: Int,
    ) = findMatches(criteria, snapshot).let { matches ->
        val occurrence = criteria["occurrence"]?.jsonPrimitive?.intOrNull ?: 0
        matches.getOrNull(occurrence)
            ?: throw McpToolException.NodeNotFound(
                "Route step $stepIndex found ${matches.size} match(es), occurrence $occurrence is unavailable",
            )
    }

    private fun findMatches(
        criteria: JsonObject,
        snapshot: MultiWindowResult,
    ) = elementFinder.findElements(
        snapshot.windows,
        parseFindBy(criteria["by"]?.jsonPrimitive?.contentOrNull ?: "text"),
        criteria["value"]?.jsonPrimitive?.contentOrNull
            ?: throw McpToolException.InvalidParams("Route criteria require a non-empty 'value'"),
        criteria["exact_match"]?.jsonPrimitive?.booleanOrNull ?: true,
    )

    private suspend fun waitForMatch(
        step: JsonObject,
        stepIndex: Int,
    ) {
        val timeout = step["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_WAIT_MS
        if (timeout !in 1..MAX_WAIT_MS) {
            throw McpToolException.InvalidParams("Route step $stepIndex timeout must be 1..$MAX_WAIT_MS ms")
        }
        val deadline = System.currentTimeMillis() + timeout
        do {
            if (findMatches(step, freshSnapshot()).isNotEmpty()) return
            delay(POLL_INTERVAL_MS)
        } while (System.currentTimeMillis() < deadline)
        throw McpToolException.NodeNotFound("Route step $stepIndex timed out after ${timeout}ms")
    }

    private fun parseFindBy(value: String): FindBy =
        when (value.lowercase()) {
            "text" -> FindBy.TEXT
            "content_desc" -> FindBy.CONTENT_DESC
            "resource_id" -> FindBy.RESOURCE_ID
            "class_name" -> FindBy.CLASS_NAME
            else -> throw McpToolException.InvalidParams("Unsupported route matcher '$value'")
        }

    private fun requiredStepString(
        step: JsonObject,
        name: String,
        index: Int,
    ): String =
        step[name]?.jsonPrimitive?.contentOrNull
            ?: throw McpToolException.InvalidParams("Route step $index is missing '$name'")

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Executes a bounded semantic route using named nodes, package guards, live node " +
                    "reacquisition, resumable skip fingerprints, region swipes, and a final assertion.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("expected_package") { put("type", "string") }
                            putJsonObject("steps") {
                                put("type", "array")
                                put("maxItems", MAX_STEPS)
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("action") {
                                            put("type", "string")
                                            put(
                                                "enum",
                                                buildJsonArray {
                                                    listOf("click", "tap", "wait", "assert", "back", "swipe_region")
                                                        .forEach { add(JsonPrimitive(it)) }
                                                },
                                            )
                                        }
                                        listOf("by", "value", "region", "direction", "distance").forEach { key ->
                                            putJsonObject(key) { put("type", "string") }
                                        }
                                        putJsonObject("exact_match") { put("type", "boolean") }
                                        putJsonObject("occurrence") { put("type", "integer") }
                                        putJsonObject("timeout") { put("type", "number") }
                                        putJsonObject("repeat") { put("type", "integer") }
                                        putJsonObject("duration") { put("type", "number") }
                                        putJsonObject("skip_if") { put("type", "object") }
                                    }
                                }
                            }
                            putJsonObject("final_assert") { put("type", "object") }
                        },
                    required = listOf("expected_package", "steps"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "run_semantic_route"
        private const val MAX_STEPS = 30
        private const val DEFAULT_WAIT_MS = 3_000L
        private const val MAX_WAIT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 150L
    }
}

@Suppress("LongParameterList")
fun registerSemanticRouteTool(
    registrar: LoggedToolRegistrar,
    treeParser: AccessibilityTreeParser,
    elementFinder: ElementFinder,
    actionExecutor: ActionExecutor,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    nodeCache: AccessibilityNodeCache,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(SemanticRouteTool.TOOL_NAME)) {
        SemanticRouteTool(treeParser, elementFinder, actionExecutor, accessibilityServiceProvider, nodeCache)
            .register(registrar, toolNamePrefix)
    }
}
