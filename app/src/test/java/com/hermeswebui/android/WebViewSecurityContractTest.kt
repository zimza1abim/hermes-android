package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Source-level guards for invariants that otherwise live only in AGENTS.md prose. These are
 * deliberately blunt: they fail on the shape of the code, not on behavior, so a future change
 * that reintroduces a documented regression is stopped before it reaches a device.
 *
 * - Issue #127: certificate validation is never bypassed in the WebView.
 * - Issue #7: Android never writes the WebUI dashboard configuration.
 * - Issues #6 / #35: no long-press interception that would break text selection.
 * - Declared permissions that WebUI voice, notifications and LAN access depend on.
 */
class WebViewSecurityContractTest {
    private val mainDir: File = listOf(File("src/main"), File("app/src/main"))
        .firstOrNull { it.isDirectory }
        ?: error("Run from the repository root or the app module")

    private val manifest: String = File(mainDir, "AndroidManifest.xml").readText()

    private val kotlinSources: Map<String, String> = File(mainDir, "java")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(mainDir).invariantSeparatorsPath to it.readText() }

    /** Sources with `//` and block comments removed so prose that mentions a rule cannot trip it. */
    private val codeOnly: Map<String, String> = kotlinSources.mapValues { (_, text) -> stripComments(text) }

    @Test
    fun `sources were found so the guards cannot pass vacuously`() {
        assertThat(kotlinSources.keys.any { it.endsWith("MainActivity.kt") }).isTrue()
        assertThat(kotlinSources.size).isGreaterThan(20)
    }

    @Test
    fun `ssl errors are always cancelled and never proceeded`() {
        val overrides = codeOnly.flatMap { (path, text) ->
            bodiesOf(text, "override fun onReceivedSslError(").map { path to it }
        }
        assertThat(overrides).isNotEmpty()
        overrides.forEach { (path, body) ->
            assertWithMessage("$path onReceivedSslError").that(body).contains("cancel()")
            assertWithMessage("$path onReceivedSslError").that(body).doesNotContain("proceed")
        }
    }

    @Test
    fun `the ssl error handler type is only used by the cancelling override`() {
        // Any other use of SslErrorHandler could call proceed() from somewhere this guard does not see.
        val uses = codeOnly.values.sumOf { text ->
            text.lines()
                .filterNot { it.trimStart().startsWith("import ") }
                .sumOf { Regex("""\bSslErrorHandler\b""").findAll(it).count() }
        }
        val overrides = codeOnly.values.sumOf { Regex("""override fun onReceivedSslError\(""").findAll(it).count() }
        assertThat(uses).isEqualTo(overrides)
    }

    @Test
    fun `android never touches the WebUI dashboard config endpoint`() {
        codeOnly.forEach { (path, text) ->
            assertWithMessage(path).that(text).doesNotContain("dashboard/config")
        }
    }

    @Test
    fun `no long click interception or synthesized context menu`() {
        val forbidden = listOf(
            "setOnLongClickListener",
            "OnLongClickListener",
            "startActionMode",
            "onCreateActionMode",
            "contextmenu",
            "touchcancel"
        )
        codeOnly.forEach { (path, text) ->
            forbidden.forEach { token ->
                assertWithMessage("$path must not use $token").that(text).doesNotContain(token)
            }
        }
        val mainActivity = codeOnly.entries.single { it.key.endsWith("/MainActivity.kt") }.value
        assertThat(mainActivity).contains("isLongClickable = true")
    }

    @Test
    fun `clipboard and image input stay on scoped message bridges with no javascript interface`() {
        // Issue #180: the clipboard fallback must use the trust-checked WebMessageListener, never
        // addJavascriptInterface, and image input must be advertised only for the configured route.
        codeOnly.forEach { (path, text) ->
            assertWithMessage("$path must not use addJavascriptInterface")
                .that(text).doesNotContain("addJavascriptInterface")
        }
        val coordinator = codeOnly.entries
            .single { it.key.endsWith("HermesClipboardBridgeCoordinator.kt") }.value
        assertThat(coordinator).contains("isTrustedSource(sourceOrigin, isMainFrame)")
        assertThat(coordinator.indexOf("isTrustedSource(sourceOrigin, isMainFrame)"))
            .isLessThan(coordinator.indexOf("primaryClip"))

        val mainActivity = codeOnly.entries.single { it.key.endsWith("MainActivity.kt") }.value
        assertThat(mainActivity).contains("acceptsImageInput = { matchesConfiguredWebUiRoute(url) }")
        val delivery = bodiesOf(mainActivity, "private fun deliverCommittedImage(").single()
        assertThat(delivery).contains("matchesConfiguredWebUiRoute(webView.url)")
        assertThat(delivery).contains("buildOriginGuardedRuntimeScript(")
    }

    @Test
    fun `switching servers wipes the old session before loading the new host`() {
        // A session from one server must never carry over to another: cookies, web storage, cache,
        // form data and history are all cleared, and the wipe runs before the profile changes.
        val mainActivity = codeOnly.entries.single { it.key.endsWith("MainActivity.kt") }.value
        val wipe = bodiesOf(mainActivity, "private fun clearWebViewStateForServerSwitch(").single()
        listOf(
            "removeAllCookies(",
            "WebStorage.getInstance().deleteAllData()",
            "clearHistory()",
            "clearCache(true)",
            "clearFormData()"
        ).forEach { call -> assertWithMessage("server-switch wipe").that(wipe).contains(call) }

        val switchBody = bodiesOf(mainActivity, "private fun performServerProfileSwitch(").single()
        val wipeAt = switchBody.indexOf("clearWebViewStateForServerSwitch()")
        val switchAt = switchBody.indexOf("viewModel.switchServerProfile(")
        assertThat(wipeAt).isAtLeast(0)
        assertThat(switchAt).isGreaterThan(wipeAt)
    }

    @Test
    fun `manifest declares the permissions WebUI features depend on`() {
        val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
        listOf(
            "android.permission.INTERNET",
            "android.permission.RECORD_AUDIO",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.ACCESS_LOCAL_NETWORK",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
        ).forEach { assertWithMessage(it).that(declared).contains(it) }
    }

    @Test
    fun `manifest does not request broad storage access`() {
        // Downloads use the shared Download folder through DownloadManager (Issue #130).
        val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
        assertThat(declared).containsNoneOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE"
        )
    }

    @Test
    fun `the webapp keep-alive service is private and uses the uncapped specialUse type`() {
        val service = Regex("""<service[^>]*android:name="\.background\.HermesKeepAliveService".*?</service>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.value
        assertWithMessage("HermesKeepAliveService manifest entry").that(service).isNotNull()
        assertThat(service).contains("android:exported=\"false\"")
        assertThat(service).contains("android:foregroundServiceType=\"specialUse\"")
        assertThat(service).contains("android:name=\"android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE\"")
        assertThat(manifest).contains("android:name=\"android.permission.FOREGROUND_SERVICE_SPECIAL_USE\"")
        val reconnect = Regex("""<service[^>]*android:name="\.background\.HermesReconnectService"[^>]*/>""")
            .find(manifest)?.value
        assertThat(reconnect).contains("android:foregroundServiceType=\"dataSync\"")
        val keepAlive = codeOnly.entries.single { it.key.endsWith("background/HermesKeepAliveService.kt") }.value
        assertThat(keepAlive).contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE")
        assertThat(keepAlive).doesNotContain("FOREGROUND_SERVICE_TYPE_DATA_SYNC")
    }

    @Test
    fun `the battery exemption is asked only from the Settings row, never automatically`() {
        val all = codeOnly.values.joinToString("\n")
        assertThat(Regex("""ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS""").findAll(all).count()).isEqualTo(1)
        val activity = codeOnly.entries.single { it.key.endsWith("/MainActivity.kt") }.value
        assertThat(activity).contains("private fun openBatteryOptimizationSettings()")
        assertThat(activity).contains("onOpenBatterySettings = ::openBatteryOptimizationSettings")
        assertThat(Regex("""\bopenBatteryOptimizationSettings\b""").findAll(activity).count()).isEqualTo(2)
    }

    @Test
    fun `webapp page scripts are origin guarded and the network callback is released with keep-alive`() {
        val coordinator = codeOnly.entries.single { it.key.endsWith("webapp/WebappGatewayCoordinator.kt") }.value
        val evaluations = Regex("""evaluateJavascript\(""").findAll(coordinator).count()
        val guarded = Regex("""evaluateJavascript\(\s*HermesWebUiScripts\.buildOriginGuardedRuntimeScript\(""").findAll(coordinator).count()
        assertThat(evaluations).isAtLeast(1)
        assertThat(guarded).isEqualTo(evaluations)
        val stop = coordinator.substringAfter("private fun stopKeepAliveService()").substringBefore("private fun ")
        assertThat(stop).contains("unregisterNetworkCallback(")
    }

    @Test
    fun `webapp tray answers stay private and Allow requires an unlocked device`() {
        val coordinator = codeOnly.entries.single { it.key.endsWith("webapp/WebappGatewayCoordinator.kt") }.value
        assertThat(coordinator).contains("ContextCompat.RECEIVER_NOT_EXPORTED")
        assertThat(coordinator).doesNotContain("RECEIVER_EXPORTED,")
        assertThat(coordinator).contains(".setPackage(activity.packageName)")
        val allow = coordinator.substringAfter("\"Allow\"").substringBefore(".build()")
        assertThat(allow).contains(".setAuthenticationRequired(true)")
        assertThat(coordinator).contains("PendingIntent.FLAG_IMMUTABLE")
        assertThat(coordinator).doesNotContain("FLAG_MUTABLE")
    }

    @Test
    fun `webapp bridge messages are trust-checked before parsing and taps never load a url`() {
        val coordinator = codeOnly.entries.single { it.key.endsWith("webapp/WebappGatewayCoordinator.kt") }.value
        val handler = bodiesOf(coordinator, "private fun onBridgeMessage(").single()
        assertThat(handler.indexOf("isTrustedSource(origin, isMainFrame)")).isAtLeast(0)
        assertThat(handler.indexOf("isTrustedSource(origin, isMainFrame)"))
            .isLessThan(handler.indexOf("WebappBridgeEnvelope.parse("))
        // Loading a URL would reload the page and drop the gateway socket the app keeps alive.
        listOf("loadUrl", "loadInMainWebView", "EXTRA_NOTIFICATION_URL").forEach { call ->
            assertWithMessage("WebappGatewayCoordinator must not $call").that(coordinator).doesNotContain(call)
        }
    }

    private fun stripComments(text: String): String {
        val noBlock = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(text, "")
        // Skip `://` so URLs inside string literals survive.
        return Regex("""(?<!:)//[^\n]*""").replace(noBlock, "")
    }

    /** Returns the brace-balanced bodies of every function that starts with [signature]. */
    private fun bodiesOf(text: String, signature: String): List<String> {
        val bodies = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = text.indexOf(signature, from)
            if (start < 0) break
            val open = text.indexOf('{', start)
            if (open < 0) break
            var depth = 0
            var end = open
            while (end < text.length) {
                when (text[end]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                end++
            }
            bodies += text.substring(open, minOf(end + 1, text.length))
            from = end
        }
        return bodies
    }
}
