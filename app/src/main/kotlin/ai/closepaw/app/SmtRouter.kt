package ai.closepaw.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest
import kotlin.math.max

/**
 * Deterministic SMT router. No LLM, API key, model inference, or web access is used.
 *
 * Flow:
 * 00 -> ROUTE_TASK -> target chat -> wait for answer -> capture answer -> 00 -> RESULT_FROM_AGENT
 */
internal class SmtRouter(private val service: AgentService) {

    companion object {
        private const val TAG = "SmtRouter"
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        private const val POLL_MS = 3_000L
        private const val UI_SETTLE_MS = 850L
        private const val GENERATION_TIMEOUT_MS = 10 * 60 * 1000L
        private const val MAX_RESPONSE_SCROLLS = 14

        private const val HQ_TITLE = "00 — SMT HQ"

        private val TARGET_TITLES = mapOf(
            "01" to "01 — SMT Brand & Creative",
            "02" to "02 — SMT Marketing & Content",
            "03" to "03 — SMT Legal & IP",
            "04" to "04 — SMT Finance",
            "05" to "05 — SMT Capital & IR",
            "06" to "06 — SMT Business & Growth",
            "07" to "07 — SMT Sales & CRM",
            "08" to "08 — SMT Architecture & Product",
            "09" to "09 — SMT Market Intelligence",
            "10" to "10 — SMT PMO & Operations",
        )

        private val routeBlockRegex = Regex(
            "ROUTE_TASK\\s.*?END_ROUTE_TASK",
            setOf(
                RegexOption.DOT_MATCHES_ALL,
                RegexOption.IGNORE_CASE
            )
        )

        private val routeIdRegex =
            Regex("(?mi)^ROUTE_ID:\\s*([^\\s]+)\\s*$")

        private val targetRegex =
            Regex("(?mi)^TARGET:\\s*(.+?)\\s*$")

        private val targetIdRegex =
            Regex("\\b(0[1-9]|10)\\b")
    }

    private val ledger = SmtRouteLedger(service)
    private var job: Job? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    fun start() {
        if (isRunning) return

        Log.i(TAG, "Starting deterministic SMT router")

        job = service.serviceScope.launch {
            runCatching {
                mainLoop()
            }.onFailure {
                Log.e(TAG, "Router loop crashed", it)
            }
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping SMT router")
        job?.cancel()
        job = null
    }

    private suspend fun mainLoop() {
        launchChatGpt()
        openConversation(HQ_TITLE)

        while (
            service.serviceScope.isActive &&
            job?.isActive == true
        ) {
            val route = findNewestRouteTask()

            if (route == null) {
                delay(POLL_MS)
                continue
            }

            if (ledger.isTerminal(route.routeId)) {
                delay(POLL_MS)
                continue
            }

            ledger.upsertPending(
                route.routeId,
                route.targetId,
                sha256(route.block)
            )

            processRoute(route)

            openConversation(HQ_TITLE)

            delay(POLL_MS)
        }
    }

    private suspend fun processRoute(route: ParsedRoute) {
        var lastError: Throwable? = null

        repeat(2) { attempt ->
            try {
                ledger.update(
                    route.routeId,
                    SmtRouteState.PENDING,
                    incrementAttempts = attempt > 0
                )

                val targetTitle =
                    TARGET_TITLES[route.targetId]
                        ?: error(
                            "Unknown SMT target ${route.targetId}"
                        )

                openConversation(targetTitle)

                sendMessage(route.block)

                ledger.update(
                    route.routeId,
                    SmtRouteState.SENT
                )

                ledger.update(
                    route.routeId,
                    SmtRouteState.WAITING_RESPONSE
                )

                waitForGenerationToFinish(
                    route.routeId
                )

                val response =
                    captureResponse(route)

                require(response.isNotBlank()) {
                    "Specialist response was empty"
                }

                ledger.update(
                    route.routeId,
                    SmtRouteState.RESULT_CAPTURED
                )

                openConversation(HQ_TITLE)

                val envelope =
                    buildString {
                        appendLine("RESULT_FROM_AGENT")
                        appendLine(
                            "ROUTE_ID: ${route.routeId}"
                        )
                        appendLine(
                            "SOURCE: ${route.targetLine}"
                        )
                        appendLine(response.trim())
                        append(
                            "END_RESULT_FROM_AGENT"
                        )
                    }

                sendMessage(envelope)

                ledger.update(
                    route.routeId,
                    SmtRouteState.RETURNED_TO_00
                )

                Log.i(
                    TAG,
                    "Completed ${route.routeId}"
                )

                return

            } catch (t: Throwable) {
                lastError = t

                Log.w(
                    TAG,
                    "Route ${route.routeId} attempt ${attempt + 1} failed",
                    t
                )

                if (attempt == 0) {
                    delay(1_250)
                    launchChatGpt()
                }
            }
        }

        ledger.update(
            route.routeId,
            SmtRouteState.FAILED,
            error =
                lastError?.message
                    ?: "Unknown router failure"
        )

        Log.e(
            TAG,
            "Route ${route.routeId} failed permanently",
            lastError
        )
    }

    private suspend fun launchChatGpt() {
        if (currentPackage() == CHATGPT_PACKAGE) {
            return
        }

        val pm = service.packageManager

        val intent =
            pm.getLaunchIntentForPackage(
                CHATGPT_PACKAGE
            )
                ?: pm.getInstalledApplications(0)
                    .firstOrNull {
                        runCatching {
                            pm.getApplicationLabel(it)
                                .toString()
                        }.getOrNull()
                            ?.equals(
                                "ChatGPT",
                                ignoreCase = true
                            ) == true
                    }
                    ?.let {
                        pm.getLaunchIntentForPackage(
                            it.packageName
                        )
                    }
                ?: error(
                    "ChatGPT app is not installed"
                )

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
        )

        service.startActivity(intent)

        delay(1_500)
    }

    private suspend fun openConversation(
        titlePrefix: String
    ) {
        launchChatGpt()

        val opened =
            clickByLabels(
                listOf(
                    "Open navigation drawer",
                    "Navigation menu",
                    "Open sidebar",
                    "Sidebar",
                    "Menu"
                )
            )

        if (!opened) {
            tap(
                dp(28),
                dp(58)
            )
        }

        delay(UI_SETTLE_MS)

        if (clickTextContains(titlePrefix)) {
            delay(UI_SETTLE_MS)
            return
        }

        if (
            clickByLabels(
                listOf(
                    "Search chats",
                    "Search conversations",
                    "Search"
                )
            )
        ) {
            delay(500)

            if (
                setFocusedOrFirstEditableText(
                    titlePrefix
                )
            ) {
                delay(850)

                if (
                    clickTextContains(
                        titlePrefix
                    )
                ) {
                    delay(UI_SETTLE_MS)
                    return
                }
            }

            service.performGlobalAction(
                AccessibilityService
                    .GLOBAL_ACTION_BACK
            )

            delay(400)
        }

        repeat(12) {
            if (
                clickTextContains(
                    titlePrefix
                )
            ) {
                delay(UI_SETTLE_MS)
                return
            }

            if (
                !scrollAny(
                    forward = true
                )
            ) {
                return@repeat
            }

            delay(350)
        }

        error(
            "Conversation not found: $titlePrefix"
        )
    }

    private suspend fun sendMessage(
        text: String
    ) {
        repeat(8) {
            if (
                setFocusedOrFirstEditableText(
                    text
                )
            ) {
                delay(350)

                if (
                    clickByLabels(
                        listOf(
                            "Send message",
                            "Send",
                            "Submit"
                        )
                    )
                ) {
                    delay(1_000)
                    return
                }

                val dm =
                    service.resources.displayMetrics

                tap(
                    dm.widthPixels - dp(42),
                    dm.heightPixels - dp(92)
                )

                delay(1_000)

                return
            }

            delay(350)
        }

        error(
            "Could not find ChatGPT message composer"
        )
    }

    private suspend fun waitForGenerationToFinish(
        routeId: String
    ) {
        val startedAt =
            System.currentTimeMillis()

        var lastFingerprint = ""
        var stablePasses = 0
        var sawActivity = false

        while (
            System.currentTimeMillis() -
            startedAt <
            GENERATION_TIMEOUT_MS
        ) {
            val text =
                screenText()

            val active =
                containsAnyLabel(
                    listOf(
                        "Stop generating",
                        "Stop response",
                        "Stop"
                    )
                )

            if (active) {
                sawActivity = true
            }

            val fingerprint =
                sha256(
                    text.takeLast(8_000)
                )

            if (
                !active &&
                fingerprint == lastFingerprint &&
                text.contains(routeId)
            ) {
                stablePasses += 1
            } else {
                stablePasses = 0
            }

            lastFingerprint =
                fingerprint

            if (
                !active &&
                stablePasses >= 3 &&
                (
                    sawActivity ||
                    System.currentTimeMillis() -
                    startedAt >
                    8_000
                )
            ) {
                return
            }

            delay(1_200)
        }

        error(
            "Timed out waiting for specialist response"
        )
    }

    private suspend fun captureResponse(
        route: ParsedRoute
    ): String {
        val copied =
            tryCopyLatestResponse()

        if (
            !copied.isNullOrBlank() &&
            !copied.contains("ROUTE_TASK")
        ) {
            return copied.trim()
        }

        val pages =
            mutableListOf<List<String>>()

        for (
            i in
            0 until MAX_RESPONSE_SCROLLS
        ) {
            val lines =
                screenLines()

            pages.add(
                0,
                lines
            )

            if (
                lines.any {
                    it.contains(
                        route.routeId
                    )
                } &&
                lines.any {
                    it.contains(
                        "ROUTE_TASK",
                        ignoreCase = true
                    )
                }
            ) {
                break
            }

            if (
                !scrollAny(
                    forward = false
                )
            ) {
                break
            }

            delay(450)
        }

        val merged =
            mergePages(pages)

        val all =
            merged.joinToString("\n")

        val marker =
            all.lastIndexOf(
                "END_ROUTE_TASK",
                ignoreCase = true
            )

        if (marker < 0) {
            error(
                "Could not locate END_ROUTE_TASK while capturing response"
            )
        }

        return cleanCapturedResponse(
            all.substring(
                marker +
                    "END_ROUTE_TASK".length
            )
        )
    }

    private suspend fun tryCopyLatestResponse():
        String? {
        val before =
            clipboardText()

        val clicked =
            clickLastByLabels(
                listOf(
                    "Copy response",
                    "Copy"
                )
            )

        if (!clicked) {
            return null
        }

        delay(450)

        val after =
            clipboardText()

        return after?.takeIf {
            it.isNotBlank() &&
            it != before
        }
    }

    private fun clipboardText():
        String? =
        runCatching {
            val clipboard =
                service.getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager

            val clip =
                clipboard.primaryClip
                    ?: return@runCatching null

            if (clip.itemCount == 0) {
                return@runCatching null
            }

            clip.getItemAt(0)
                .coerceToText(service)
                ?.toString()

        }.getOrNull()

    private fun findNewestRouteTask():
        ParsedRoute? {
        val text =
            screenText()

        val matches =
            routeBlockRegex
                .findAll(text)
                .toList()

        if (matches.isEmpty()) {
            return null
        }

        for (
            match in
            matches.asReversed()
        ) {
            val block =
                match.value.trim()

            val routeId =
                routeIdRegex
                    .find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?: continue

            val targetLine =
                targetRegex
                    .find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?: continue

            val targetId =
                targetIdRegex
                    .find(targetLine)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: continue

            return ParsedRoute(
                routeId,
                targetId,
                targetLine,
                block
            )
        }

        return null
    }

    private fun cleanCapturedResponse(
        raw: String
    ): String {
        val chrome =
            setOf(
                "Copy",
                "Copy response",
                "Share",
                "Read aloud",
                "Good response",
                "Bad response",
                "Regenerate",
                "Retry",
                "Edit",
                "Send",
                "Ask anything",
                "ChatGPT can make mistakes."
            )

        return raw
            .lineSequence()
            .map {
                it.trim()
            }
            .filter {
                it.isNotBlank()
            }
            .filterNot {
                it in chrome
            }
            .joinToString("\n")
            .trim()
    }

    private fun mergePages(
        pages: List<List<String>>
    ): List<String> {
        val out =
            mutableListOf<String>()

        for (page in pages) {
            if (page.isEmpty()) {
                continue
            }

            val maxOverlap =
                minOf(
                    out.size,
                    page.size,
                    80
                )

            var overlap = 0

            for (
                candidate in
                maxOverlap downTo 1
            ) {
                if (
                    out.takeLast(candidate) ==
                    page.take(candidate)
                ) {
                    overlap =
                        candidate

                    break
                }
            }

            out.addAll(
                page.drop(overlap)
            )
        }

        return out
    }

    private fun screenText():
        String =
        screenLines()
            .joinToString("\n")

    private fun screenLines():
        List<String> {
        val root =
            service.rootInActiveWindow
                ?: return emptyList()

        val lines =
            mutableListOf<String>()

        walk(root) { node ->
            node.text
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let(
                    lines::add
                )

            node.contentDescription
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let(
                    lines::add
                )
        }

        return lines.fold(
            mutableListOf()
        ) { acc, line ->
            if (
                acc.lastOrNull() !=
                line
            ) {
                acc.add(line)
            }

            acc
        }
    }

    private fun currentPackage():
        String? =
        service.rootInActiveWindow
            ?.packageName
            ?.toString()

    private fun containsAnyLabel(
        labels: List<String>
    ): Boolean {
        val wanted =
            labels.map {
                it.lowercase()
            }

        var found = false

        val root =
            service.rootInActiveWindow
                ?: return false

        walk(root) { node ->
            if (found) {
                return@walk
            }

            val values =
                listOfNotNull(
                    node.text
                        ?.toString(),
                    node.contentDescription
                        ?.toString()
                )

            if (
                values.any { value ->
                    wanted.any {
                        value.contains(
                            it,
                            ignoreCase = true
                        )
                    }
                }
            ) {
                found = true
            }
        }

        return found
    }

    private fun clickTextContains(
        text: String
    ): Boolean {
        val candidates =
            mutableListOf<
                AccessibilityNodeInfo
            >()

        val root =
            service.rootInActiveWindow
                ?: return false

        walk(root) { node ->
            val value =
                node.text
                    ?.toString()
                    .orEmpty()

            val desc =
                node.contentDescription
                    ?.toString()
                    .orEmpty()

            if (
                value.contains(
                    text,
                    ignoreCase = true
                ) ||
                desc.contains(
                    text,
                    ignoreCase = true
                )
            ) {
                candidates.add(node)
            }
        }

        return candidates
            .asReversed()
            .any {
                clickNodeOrParent(it)
            }
    }

    private fun clickByLabels(
        labels: List<String>
    ): Boolean {
        val candidates =
            collectLabelCandidates(
                labels
            )

        return candidates.any {
            clickNodeOrParent(it)
        }
    }

    private fun clickLastByLabels(
        labels: List<String>
    ): Boolean {
        val candidates =
            collectLabelCandidates(
                labels
            )

        return candidates
            .asReversed()
            .any {
                clickNodeOrParent(it)
            }
    }

    private fun collectLabelCandidates(
        labels: List<String>
    ): List<AccessibilityNodeInfo> {
        val wanted =
            labels.map {
                it.lowercase()
            }

        val candidates =
            mutableListOf<
                AccessibilityNodeInfo
            >()

        val root =
            service.rootInActiveWindow
                ?: return candidates

        walk(root) { node ->
            val values =
                listOfNotNull(
                    node.text
                        ?.toString(),
                    node.contentDescription
                        ?.toString()
                )

            if (
                values.any { value ->
                    wanted.any {
                            wantedLabel ->
                        value.equals(
                            wantedLabel,
                            true
                        ) ||
                        value.contains(
                            wantedLabel,
                            true
                        )
                    }
                }
            ) {
                candidates.add(node)
            }
        }

        return candidates
    }

    private fun clickNodeOrParent(
        node: AccessibilityNodeInfo
    ): Boolean {
        var current:
            AccessibilityNodeInfo? =
            node

        repeat(5) {
            val n =
                current
                    ?: return false

            if (
                n.isClickable &&
                n.isEnabled &&
                n.performAction(
                    AccessibilityNodeInfo
                        .ACTION_CLICK
                )
            ) {
                return true
            }

            current =
                n.parent
        }

        return false
    }

    private fun setFocusedOrFirstEditableText(
        text: String
    ): Boolean {
        val editable =
            mutableListOf<
                AccessibilityNodeInfo
            >()

        val root =
            service.rootInActiveWindow
                ?: return false

        walk(root) { node ->
            if (
                node.isEnabled &&
                (
                    node.isEditable ||
                    node.className
                        ?.toString()
                        ?.contains(
                            "EditText"
                        ) == true
                )
            ) {
                editable.add(node)
            }
        }

        val target =
            editable.firstOrNull {
                it.isFocused
            }
                ?: editable.firstOrNull { n ->

                    val descriptor =
                        "${n.hintText?.toString().orEmpty()} " +
                        "${n.contentDescription?.toString().orEmpty()} " +
                        n.text?.toString().orEmpty()

                    descriptor.contains(
                        "message",
                        true
                    ) ||
                    descriptor.contains(
                        "ask",
                        true
                    ) ||
                    descriptor.contains(
                        "search",
                        true
                    )
                }
                ?: editable.lastOrNull()
                ?: return false

        val args =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo
                        .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }

        return target.performAction(
            AccessibilityNodeInfo
                .ACTION_SET_TEXT,
            args
        )
    }

    private fun scrollAny(
        forward: Boolean
    ): Boolean {
        val root =
            service.rootInActiveWindow
                ?: return false

        val scrollables =
            mutableListOf<
                AccessibilityNodeInfo
            >()

        walk(root) {
            if (
                it.isScrollable &&
                it.isEnabled
            ) {
                scrollables.add(it)
            }
        }

        val action =
            if (forward) {
                AccessibilityNodeInfo
                    .ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo
                    .ACTION_SCROLL_BACKWARD
            }

        return scrollables
            .asReversed()
            .any {
                it.performAction(action)
            }
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        visitor: (AccessibilityNodeInfo) -> Unit
    ) {
        visitor(node)

        for (
            i in
            0 until node.childCount
        ) {
            val child =
                node.getChild(i)
                    ?: continue

            walk(
                child,
                visitor
            )
        }
    }

    private suspend fun tap(
        x: Int,
        y: Int
    ) {
        val path =
            Path().apply {
                moveTo(
                    x.toFloat(),
                    y.toFloat()
                )

                lineTo(
                    x.toFloat(),
                    y.toFloat()
                )
            }

        val gesture =
            GestureDescription
                .Builder()
                .addStroke(
                    GestureDescription
                        .StrokeDescription(
                            path,
                            0,
                            80
                        )
                )
                .build()

        service.dispatchGesture(
            gesture,
            null,
            null
        )

        delay(180)
    }

    private fun dp(
        value: Int
    ): Int =
        max(
            1,
            (
                value *
                service.resources
                    .displayMetrics
                    .density
            ).toInt()
        )

    private fun sha256(
        value: String
    ): String {
        val digest =
            MessageDigest
                .getInstance(
                    "SHA-256"
                )
                .digest(
                    value.toByteArray()
                )

        return digest.joinToString(
            ""
        ) {
            "%02x".format(it)
        }
    }

    private data class ParsedRoute(
        val routeId: String,
        val targetId: String,
        val targetLine: String,
        val block: String,
    )
}
