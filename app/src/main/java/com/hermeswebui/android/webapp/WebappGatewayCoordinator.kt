package com.hermeswebui.android.webapp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hermeswebui.android.R
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.background.HermesKeepAliveService
import com.hermeswebui.android.webui.HermesWebUiScripts
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest

/**
 * The official Webapp in the background: keep-alive, renderer priority, network-change nudges, the
 * native gateway monitor, gateway alerts and tray answers. MainActivity feeds it visibility, document
 * starts, settings and intents; every decision comes from [WebappAlertReducer], and this class only
 * renders its effects.
 */
class WebappGatewayCoordinator private constructor(
    private val context: Context,
    initialSettings: WebappBackgroundSettings,
) {
    private val bridgeName = "HermesAndroidGateway"
    private var isTrustedSource: (Uri, Boolean) -> Boolean = { _, _ -> false }
    private var trustedPageOrigin: () -> String? = { null }
    private val repository = SettingsRepository(context)
    private val serverUrl: () -> String? = { repository.getSettings("", "").serverUrl }
    private var previousServerUrl = serverUrl()
    private val watchPrefs = context.getSharedPreferences("hermes_monitor_watch", Context.MODE_PRIVATE)
    private var lastWatchSnapshot: String? = null

    private var uiOwner: Any? = null

    fun attachUi(owner: Any, trusted: (Uri, Boolean) -> Boolean, origin: () -> String?) {
        uiOwner = owner
        isTrustedSource = trusted
        trustedPageOrigin = origin
    }

    /** A restarted service can restore monitoring without an Activity or WebView. */
    fun startHeadless() {
        ensureReceiver()
        restoreWatch()
        dispatch(WebappInput.Hello(DocId("service")))
        dispatch(WebappInput.Visibility(false))
        dispatch(WebappInput.DocumentGone)
    }

    /** Document-start and runtime tap script, built from the parser's own marker list. */
    val tapScript: String = HermesWebUiScripts.buildWebappGatewayTapScript(bridgeName, GatewayProtocol.WATCHED_MARKERS)

    private var state = WebappState(initialSettings)
    private val pending = ArrayDeque<WebappInput>()
    private var dispatching = false

    private var webView: WebView? = null
    private var replyProxy: JavaScriptReplyProxy? = null
    private var proxyDoc: DocId? = null
    private var receiverRegistered = false
    private val pageAnswerTimeouts = mutableMapOf<RpcId, Runnable>()

    private val handler = Handler(Looper.getMainLooper())
    private var keepAliveRunning = false
    private val deferredStop = Runnable { stopKeepAliveService() }
    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
    private var networkWatch: NetworkWatch? = null
    /** Alerts posted (or cancelled, as null) during the current dispatch, by notification id. */
    private val trayChanges = mutableMapOf<Int, ShownAlert?>()
    private val largeIcon by lazy {
        val size = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
        ContextCompat.getDrawable(context, R.drawable.hermes_agent_icon)?.toBitmap(size, size)
    }
    private val monitor = GatewayMonitorLink(context, handler, ::dispatch)

    private val answerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prompt = intent.getStringExtra(EXTRA_PROMPT)?.let(RpcId::fromToken) ?: return
            val reply = intent.getStringExtra(EXTRA_DECISION)
                ?.let { name -> Decision.entries.firstOrNull { it.name == name } }
                ?.let(TrayReply::Decide)
                ?: intent.getIntExtra(EXTRA_CHOICE, -1).takeIf { it >= 0 }?.let(TrayReply::Choose)
                ?: RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.let(TrayReply::Reply)
                ?: return
            dispatch(WebappInput.TrayAnswer(AnswerTarget(prompt), reply))
        }
    }

    /** Attaches the main WebView. After a renderer loss, call again with the replacement, then [onDocumentStarted]. */
    fun install(view: WebView) {
        webView = view
        // Keep the renderer important even while the app is on screen: a phone under memory pressure
        // otherwise reaps it first, and every reap is a page reload and a gateway reconnect.
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        ensureReceiver()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching {
            WebViewCompat.addWebMessageListener(view, bridgeName, setOf("*")) { _, message, origin, isMainFrame, proxy ->
                handler.post { onBridgeMessage(message.data, origin, isMainFrame, proxy) }
            }
        }
    }

    private fun ensureReceiver() {
        if (!receiverRegistered) {
            createChannels(context)
            ContextCompat.registerReceiver(
                context,
                answerReceiver,
                IntentFilter(ACTION_ANSWER).apply { addDataScheme(ANSWER_SCHEME) },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    fun onVisibilityChanged(visible: Boolean) {
        dispatch(WebappInput.Visibility(visible))
        // The app on screen shows everything the tray says, and a summary can outlive the tapped child.
        if (visible) {
            // The monitor has stopped; discard its old cursor before the next background run.
            monitor.clearReplayCursors(serverUrl())
            clearTray()
        }
    }

    /** The main frame started a new document; the old one and its sockets are gone. */
    fun onDocumentStarted() {
        replyProxy = null
        proxyDoc = null
        val currentServerUrl = serverUrl()
        if (currentServerUrl != previousServerUrl) {
            previousServerUrl = currentServerUrl
            dispatch(WebappInput.ServerChanged)
        } else dispatch(WebappInput.DocumentGone)
    }

    fun updateSettings(settings: WebappBackgroundSettings) {
        if (settings != state.settings) dispatch(WebappInput.SettingsChanged(settings))
    }

    /**
     * True for a Webapp notification tap, which brings the task forward and never loads a URL. An
     * alert tap also names its session, which the page is asked to open once the app is visible.
     */
    fun handleIntent(intent: Intent?): Boolean {
        if (intent?.action != HermesKeepAliveService.ACTION_OPEN_APP) return false
        intent.getStringExtra(HermesKeepAliveService.EXTRA_SESSION_ID)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_SESSION_ID }
            ?.let { dispatch(WebappInput.FocusRequested(it)) }
        return true
    }

    /** Detach the Activity without taking down the background monitor. */
    fun dispose(owner: Any) {
        if (uiOwner !== owner) return
        uiOwner = null
        onDocumentStarted()
        isTrustedSource = { _, _ -> false }
        trustedPageOrigin = { null }
        webView = null
    }

    private fun onBridgeMessage(data: String?, origin: Uri, isMainFrame: Boolean, proxy: JavaScriptReplyProxy) {
        if (!isTrustedSource(origin, isMainFrame)) return
        val input = WebappBridgeEnvelope.parse(data) ?: return
        val messageDoc = runCatching { JSONObject(data ?: return).optString("doc") }.getOrNull()
        if (input !is WebappInput.Hello && messageDoc != state.doc?.value) return
        val answerId = when (input) {
            is WebappInput.PageAck -> input.rpcId
            is WebappInput.SendResult -> input.rpcId
            else -> null
        }
        answerId?.let { pageAnswerTimeouts.remove(it)?.let(handler::removeCallbacks) }
        dispatch(input)
        val doc = state.doc
        if (doc != null && input.docId() == doc) {
            replyProxy = proxy
            proxyDoc = doc
        }
    }

    private fun dispatch(input: WebappInput) {
        pending.addLast(input)
        if (dispatching) return
        dispatching = true
        try {
            while (pending.isNotEmpty()) {
                val (next, effects) = WebappAlertReducer.reduce(state, pending.removeFirst())
                state = next
                effects.forEach(::execute)
            }
            if (trayChanges.isNotEmpty()) refreshGroups(trayChanges.toMap())
            persistWatch()
        } finally {
            trayChanges.clear()
            dispatching = false
        }
    }

    private fun watchKey(): String? = serverUrl()?.takeIf { it.isNotBlank() }?.let { url ->
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun persistWatch() {
        if (state.visible || !state.webappActive) return
        val key = watchKey() ?: return
        val ids = WebappAlertReducer.watchSet(state).toSet()
        val rows = JSONArray()
        state.sessions.forEach { (runtime, facts) ->
            if (facts.storedId in ids) rows.put(JSONObject().put("runtime", runtime).put("stored", facts.storedId))
        }
        val data = rows.toString()
        if (lastWatchSnapshot == "$key:$data") return
        lastWatchSnapshot = "$key:$data"
        watchPrefs.edit().putString(key, data).apply()
    }

    private fun restoreWatch() {
        val data = watchKey()?.let { watchPrefs.getString(it, null) } ?: return
        val rows = runCatching { JSONArray(data) }.getOrNull() ?: return
        val sessions = linkedMapOf<String, SessionFacts>()
        for (i in 0 until minOf(rows.length(), WebappState.MAX_SESSIONS)) {
            val row = rows.optJSONObject(i) ?: continue
            val runtime = row.optString("runtime").takeIf { it.isNotBlank() && it.length <= MAX_SESSION_ID } ?: continue
            val stored = row.optString("stored").takeIf { it.isNotBlank() && it.length <= MAX_SESSION_ID } ?: continue
            sessions[runtime] = SessionFacts(storedId = stored, running = true)
        }
        state = state.copy(sessions = sessions)
    }

    private fun execute(effect: WebappEffect) {
        when (effect) {
            is WebappEffect.Post -> notify(effect.alert, effect.update)
            is WebappEffect.Cancel -> {
                val id = effect.kind.notificationId(effect.key)
                NotificationManagerCompat.from(context).cancel(GatewayAlertKind.ALERT_TAG, id)
                trayChanges[id] = null
            }
            is WebappEffect.Answer -> when (val via = effect.via) {
                is AnswerRoute.Page -> sendOnPage(via, effect)
                is AnswerRoute.Monitor -> if (!monitor.sendAnswer(via.gen, effect.rpcId, effect.frame)) {
                    dispatch(WebappInput.SendResult(effect.rpcId, false))
                }
            }
            WebappEffect.QuietWatchFallback ->
                notifyWarning(R.string.webapp_quiet_fallback_title, R.string.webapp_quiet_fallback_body, QUIET_WARNING_ID)
            WebappEffect.SignInNeeded ->
                notifyWarning(R.string.webapp_signin_needed_title, R.string.webapp_signin_needed_body, SIGNIN_WARNING_ID)
            is WebappEffect.KeepAlive -> keepAlive(effect.on)
            is WebappEffect.MonitorMint -> {
                val url = serverUrl()
                monitor.mint(effect.gen, url, url?.let { runCatching { CookieManager.getInstance().getCookie(it) }.getOrNull() })
            }
            is WebappEffect.MonitorDial -> monitor.dial(effect.gen, effect.ticket)
            is WebappEffect.MonitorSend -> if (!monitor.send(effect.gen, effect.frame)) {
                dispatch(WebappInput.MonitorClosed(effect.gen))
            }
            is WebappEffect.MonitorClose -> monitor.close(effect.gen)
            is WebappEffect.MonitorRetryAfter -> monitor.retryAfter(effect.gen, effect.delayMs)
            // Posted so it runs after the activity resumes and the WebView is shown again.
            is WebappEffect.FocusSession -> handler.post { focusSession(effect.sessionId) }
        }
    }

    /** Runs the page's own notification click for the session, which opens it in place without a reload. */
    private fun focusSession(sessionId: String) {
        val view = webView ?: return
        val origin = trustedPageOrigin() ?: return
        view.evaluateJavascript(
            HermesWebUiScripts.buildOriginGuardedRuntimeScript(
                origin,
                HermesWebUiScripts.buildWebappFocusSessionScript(sessionId)
            ),
            null
        )
    }

    /** Hands the answer to the tap, which reports `sent` or `sendFailed` for the request id. */
    private fun sendOnPage(via: AnswerRoute.Page, effect: WebappEffect.Answer) {
        val proxy = replyProxy.takeIf { proxyDoc == via.doc }
        val message = JSONObject()
            .put("t", "send")
            .put("doc", via.doc.value)
            .put("sock", via.sock)
            .put("key", effect.rpcId.json)
            .put("frame", JSONObject(effect.frame).put("hermes_android_ack", true).toString())
            .toString()
        val sent = proxy != null && runCatching { proxy.postMessage(message) }.isSuccess
        if (!sent) dispatch(WebappInput.SendResult(effect.rpcId, ok = false))
        else {
            pageAnswerTimeouts.remove(effect.rpcId)?.let(handler::removeCallbacks)
            val timeout = Runnable {
                pageAnswerTimeouts.remove(effect.rpcId)
                dispatch(WebappInput.SendResult(effect.rpcId, ok = false, uncertain = true))
            }
            pageAnswerTimeouts[effect.rpcId] = timeout
            handler.postDelayed(timeout, 30_000L)
        }
    }

    private fun keepAlive(on: Boolean) {
        handler.removeCallbacks(deferredStop)
        if (on) {
            if (!keepAliveRunning) keepAliveRunning = HermesKeepAliveService.start(context)
            watchNetwork()
        } else if (state.visible) {
            stopKeepAliveService()
        } else {
            // Hidden, but the document went away (a reload in the background). A new document
            // usually follows within seconds, and a hidden app may not be allowed to start again.
            handler.postDelayed(deferredStop, KEEP_ALIVE_STOP_GRACE_MS)
        }
    }

    private fun stopKeepAliveService() {
        keepAliveRunning = false
        HermesKeepAliveService.stop(context)
        networkWatch?.let { watch ->
            watch.cancel()
            runCatching { connectivity?.unregisterNetworkCallback(watch) }
        }
        networkWatch = null
    }

    private fun watchNetwork() {
        if (networkWatch != null) return
        val manager = connectivity ?: return
        val watch = NetworkWatch(manager.activeNetwork)
        if (runCatching { manager.registerDefaultNetworkCallback(watch, handler) }.isSuccess) networkWatch = watch
    }

    /** Tells a live gateway document that the network changed, so it redials now instead of after its throttled timers. */
    private fun nudgeOnline() {
        val view = webView ?: return
        if (state.doc == null) return
        val origin = trustedPageOrigin() ?: return
        view.evaluateJavascript(
            HermesWebUiScripts.buildOriginGuardedRuntimeScript(origin, HermesWebUiScripts.webappOnlineNudgeScript),
            null
        )
    }

    /** Default-network callback, live only while keep-alive runs, so it outlives the monitor. Callbacks arrive on [handler]. */
    private inner class NetworkWatch(initial: Network?) : ConnectivityManager.NetworkCallback() {
        private val identity = NetworkIdentity(initial)
        private var latest: Network? = initial
        private val settle = Runnable {
            if (identity.settle(latest)) {
                nudgeOnline()
                dispatch(WebappInput.NetworkChanged)
            }
        }

        override fun onAvailable(network: Network) {
            latest = network
            debounce()
        }

        override fun onLost(network: Network) {
            if (network != latest) return
            latest = null
            debounce()
        }

        fun cancel() = handler.removeCallbacks(settle)

        private fun debounce() {
            handler.removeCallbacks(settle)
            handler.postDelayed(settle, NETWORK_SETTLE_MS)
        }
    }

    private fun clearTray() {
        val manager = NotificationManagerCompat.from(context)
        runCatching { context.getSystemService(NotificationManager::class.java)?.activeNotifications }.getOrNull()
            ?.filter { it.tag == GatewayAlertKind.ALERT_TAG || it.tag == SUMMARY_TAG || it.tag == QUIET_WARNING_TAG }
            ?.forEach { manager.cancel(it.tag, it.id) }
    }

    @SuppressLint("MissingPermission")
    private fun notifyWarning(titleRes: Int, bodyRes: Int, id: Int) {
        if (!HermesKeepAliveService.canPostNotifications(context) || state.visible) return
        val body = context.getString(bodyRes)
        val notification = NotificationCompat.Builder(context, QUIET_WARNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(titleRes))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(HermesKeepAliveService.openAppIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(QUIET_WARNING_TAG, id, notification) }
    }

    @SuppressLint("MissingPermission")
    private fun notify(alert: Alert, update: Boolean) {
        if (!HermesKeepAliveService.canPostNotifications(context)) return
        val id = alert.kind.notificationId(alert.key)
        if (update && !isShown(id)) return
        val title = alert.title ?: context.getString(R.string.app_name)
        val reason = context.getString(alert.reason.labelRes)
        val publicVersion = NotificationCompat.Builder(context, alert.kind.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(reason)
            .build()
        val builder = NotificationCompat.Builder(context, alert.kind.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand_sky))
            .setLargeIcon(largeIcon)
            .setContentTitle(title)
            .setSubText(reason)
            .setOnlyAlertOnce(update)
            .setContentIntent(
                HermesKeepAliveService.openAppIntent(context, alert.sessionId, alert.kind.notificationId(alert.key))
            )
            .setAutoCancel(true)
            .setCategory(if (alert.kind.isPrompt) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setPriority(
                if (alert.kind.importance >= NotificationManager.IMPORTANCE_HIGH) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setGroup(AlertGroups.key(alert.sessionId))
        alert.body?.let { body -> builder.setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)) }
        when (val actions = alert.actions) {
            is AlertActions.Decide -> {
                builder.addAction(
                    NotificationCompat.Action.Builder(0, "Allow", decisionIntent(actions.target, Decision.ALLOW))
                        .setAuthenticationRequired(true)
                        .build()
                )
                builder.addAction(
                    NotificationCompat.Action.Builder(0, "Deny", decisionIntent(actions.target, Decision.DENY)).build()
                )
            }
            // A clarify answer can steer the agent as much as Allow, so each needs an unlocked device.
            is AlertActions.Choose -> actions.choices.forEachIndexed { index, choice ->
                builder.addAction(
                    NotificationCompat.Action.Builder(0, choice, choiceIntent(actions.target, index))
                        .setAuthenticationRequired(true)
                        .build()
                )
            }
            is AlertActions.Reply -> builder.addAction(
                NotificationCompat.Action.Builder(0, "Reply", replyIntent(actions.target))
                    .setAuthenticationRequired(true)
                    .setAllowGeneratedReplies(false)
                    .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Your answer").build())
                    .build()
            )
            null -> Unit
        }
        val posted = runCatching {
            NotificationManagerCompat.from(context).notify(GatewayAlertKind.ALERT_TAG, id, builder.build())
        }.isSuccess
        if (posted) {
            trayChanges[id] = ShownAlert(
                id, AlertGroups.key(alert.sessionId), title, digest(reason, alert.body), alert.kind.channelId,
                System.currentTimeMillis(),
            )
        }
    }

    /** Whether alert [id] is in the tray, counting this dispatch's own posts and cancels. */
    private fun isShown(id: Int): Boolean {
        if (id in trayChanges) return trayChanges[id] != null
        return runCatching {
            context.getSystemService(NotificationManager::class.java)?.activeNotifications
                ?.any { it.tag == GatewayAlertKind.ALERT_TAG && it.id == id }
        }.getOrNull() == true
    }

    /**
     * Posts or withdraws per-session summaries. The tray is read back from Android, overlaid with this
     * dispatch's own posts and cancels, which Android may not have applied yet.
     */
    private fun refreshGroups(changes: Map<Int, ShownAlert?>) {
        if (!HermesKeepAliveService.canPostNotifications(context)) return
        val active = runCatching {
            context.getSystemService(NotificationManager::class.java)?.activeNotifications?.toList()
        }.getOrNull().orEmpty()
        val shown = active.filter { it.tag == GatewayAlertKind.ALERT_TAG }.mapNotNull { it.toShownAlert() }.associateBy { it.id }
        val touched = changes.mapNotNullTo(mutableSetOf()) { (id, alert) -> (alert ?: shown[id])?.group }
        val summarized = active.filter { it.tag == SUMMARY_TAG }.mapNotNullTo(mutableSetOf()) { it.notification.group }
        val plan = AlertGroups.plan((shown + changes).values.filterNotNull(), summarized, touched)
        plan.cancel.forEach { group -> NotificationManagerCompat.from(context).cancel(SUMMARY_TAG, AlertGroups.summaryId(group)) }
        plan.post.forEach(::notifySummary)
    }

    @SuppressLint("MissingPermission")
    private fun notifySummary(summary: GroupSummary) {
        val text = context.resources.getQuantityString(R.plurals.webapp_alert_summary_count, summary.count, summary.count)
        val publicVersion = NotificationCompat.Builder(context, summary.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .build()
        val style = NotificationCompat.InboxStyle().setSummaryText(text)
        summary.lines.forEach(style::addLine)
        val notification = NotificationCompat.Builder(context, summary.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand_sky))
            .setLargeIcon(largeIcon)
            .setContentTitle(summary.title)
            .setContentText(text)
            .setStyle(style)
            .setGroup(summary.group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                HermesKeepAliveService.openAppIntent(context, summary.sessionId, AlertGroups.summaryId(summary.group))
            )
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(SUMMARY_TAG, AlertGroups.summaryId(summary.group), notification)
        }
    }

    private fun decisionIntent(target: AnswerTarget, decision: Decision): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            answerIntent(target, decision.name).putExtra(EXTRA_DECISION, decision.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun choiceIntent(target: AnswerTarget, index: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            answerIntent(target, "choice-$index").putExtra(EXTRA_CHOICE, index),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** The only mutable answer intent: Android writes the typed text into it. It stays package-scoped. */
    private fun replyIntent(target: AnswerTarget): PendingIntent {
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context, 0, answerIntent(target, "reply"), PendingIntent.FLAG_UPDATE_CURRENT or mutable
        )
    }

    private fun answerIntent(target: AnswerTarget, slot: String): Intent =
        Intent(ACTION_ANSWER)
            .setPackage(context.packageName)
            // Distinct data per prompt and action keeps Android from reusing another prompt's PendingIntent.
            .setData(
                Uri.Builder().scheme(ANSWER_SCHEME).authority(ANSWER_AUTHORITY)
                    .appendPath(target.rpcId.json).appendPath(slot).build()
            )
            .putExtra(EXTRA_PROMPT, target.rpcId.json)

    companion object {
        private var shared: WebappGatewayCoordinator? = null

        @Synchronized
        fun obtain(context: Context): WebappGatewayCoordinator = shared ?: run {
            val app = context.applicationContext
            WebappGatewayCoordinator(app, SettingsRepository(app).getWebappBackgroundSettings()).also { shared = it }
        }

        @Synchronized
        fun restoreService(context: Context) {
            if (shared == null) obtain(context).startHeadless()
        }

        const val ACTION_ANSWER = "com.hermeswebui.android.action.WEBAPP_ANSWER"
        private const val ANSWER_SCHEME = "hermes-webapp-answer"
        private const val ANSWER_AUTHORITY = "prompt"
        private const val EXTRA_PROMPT = "com.hermeswebui.android.extra.WEBAPP_PROMPT"
        private const val EXTRA_DECISION = "com.hermeswebui.android.extra.WEBAPP_DECISION"
        private const val EXTRA_CHOICE = "com.hermeswebui.android.extra.WEBAPP_CHOICE"
        private const val KEY_REPLY = "com.hermeswebui.android.extra.WEBAPP_REPLY"
        private const val KEEP_ALIVE_STOP_GRACE_MS = 15_000L
        private const val NETWORK_SETTLE_MS = 1_000L
        private const val MAX_SESSION_ID = 256
        private const val SUMMARY_TAG = "hermes.webapp.summary"
        private const val QUIET_WARNING_TAG = "hermes.webapp.quiet_fallback"
        private const val QUIET_WARNING_CHANNEL = "hermes_webapp_compatibility"
        private const val QUIET_WARNING_ID = 20_022
        private const val SIGNIN_WARNING_ID = 20_023

        private fun StatusBarNotification.toShownAlert(): ShownAlert? {
            val group = notification.group ?: return null
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val reason = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
            val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            return ShownAlert(id, group, title, digest(reason, body), notification.channelId, postTime)
        }

        /** A summary line: the reason, then the body's first line when previews show one. */
        private fun digest(reason: String, body: String?): String {
            val first = body?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            return if (first == null) reason else "$reason · $first"
        }

        private val AlertReason.labelRes: Int
            get() = when (this) {
                AlertReason.COMPLETE -> R.string.webapp_alert_complete
                AlertReason.ERROR -> R.string.webapp_alert_error
                AlertReason.INTERRUPTED -> R.string.webapp_alert_interrupted
                AlertReason.APPROVAL -> R.string.webapp_alert_approval
                AlertReason.QUESTION -> R.string.webapp_alert_question
                AlertReason.BACKGROUND -> R.string.webapp_alert_background
            }

        /** One channel per [GatewayAlertKind] plus the keep-alive channel, grouped as "Hermes Agent". */
        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannelGroup(
                NotificationChannelGroup(HermesKeepAliveService.CHANNEL_GROUP_ID, "Hermes Agent")
            )
            manager.createNotificationChannel(
                NotificationChannel(QUIET_WARNING_CHANNEL, "Gateway compatibility", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Problems affecting the background gateway connection"
                    group = HermesKeepAliveService.CHANNEL_GROUP_ID
                }
            )
            GatewayAlertKind.entries.forEach { kind ->
                manager.createNotificationChannel(
                    NotificationChannel(kind.channelId, kind.label, kind.importance).apply {
                        description = kind.summary
                        group = HermesKeepAliveService.CHANNEL_GROUP_ID
                    }
                )
            }
            HermesKeepAliveService.ensureChannel(context)
        }

        private fun WebappInput.docId(): DocId? = when (this) {
            is WebappInput.PageAck -> doc
            is WebappInput.Hello -> doc
            is WebappInput.Inbound -> doc
            is WebappInput.Responded -> doc
            is WebappInput.SocketClosed -> doc
            else -> null
        }
    }
}
