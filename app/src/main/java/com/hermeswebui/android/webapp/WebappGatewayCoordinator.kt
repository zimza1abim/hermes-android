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
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hermeswebui.android.R
import com.hermeswebui.android.background.HermesKeepAliveService
import com.hermeswebui.android.webui.HermesWebUiScripts
import org.json.JSONObject

/**
 * The official Webapp in the background: keep-alive, renderer priority, network-change nudges,
 * gateway alerts and tray answers. MainActivity feeds it visibility, document starts, settings and
 * intents; every decision comes from [WebappAlertReducer], and this class only renders its effects.
 */
class WebappGatewayCoordinator(
    private val activity: ComponentActivity,
    private val bridgeName: String,
    private val isTrustedSource: (Uri, Boolean) -> Boolean,
    private val trustedPageOrigin: () -> String?,
    initialSettings: WebappBackgroundSettings,
) {
    /** Document-start and runtime tap script, built from the parser's own marker list. */
    val tapScript: String = HermesWebUiScripts.buildWebappGatewayTapScript(bridgeName, GatewayProtocol.WATCHED_MARKERS)

    private var state = WebappState(initialSettings)
    private val pending = ArrayDeque<WebappInput>()
    private var dispatching = false

    private var webView: WebView? = null
    private var originalRendererPriority: Pair<Int, Boolean>? = null
    private var replyProxy: JavaScriptReplyProxy? = null
    private var proxyDoc: DocId? = null
    private var receiverRegistered = false

    private val handler = Handler(Looper.getMainLooper())
    private var keepAliveRunning = false
    private val deferredStop = Runnable { stopKeepAliveService() }
    private val connectivity: ConnectivityManager? = activity.getSystemService(ConnectivityManager::class.java)
    private var networkWatch: NetworkWatch? = null
    /** Alerts posted (or cancelled, as null) during the current dispatch, by notification id. */
    private val trayChanges = mutableMapOf<Int, ShownAlert?>()
    private val groupSweep = Runnable { refreshGroups(emptyMap()) }

    private val answerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val doc = intent.getStringExtra(EXTRA_DOC)?.let(::DocId) ?: return
            val prompt = intent.getStringExtra(EXTRA_PROMPT)?.let(PromptKey::decode) ?: return
            val reply = intent.getStringExtra(EXTRA_DECISION)
                ?.let { name -> Decision.entries.firstOrNull { it.name == name } }
                ?.let(TrayReply::Decide)
                ?: intent.getIntExtra(EXTRA_CHOICE, -1).takeIf { it >= 0 }?.let(TrayReply::Choose)
                ?: RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.let(TrayReply::Reply)
                ?: return
            dispatch(WebappInput.TrayAnswer(AnswerTarget(doc, prompt), reply))
        }
    }

    /** Attaches the main WebView. After a renderer loss, call again with the replacement, then [onDocumentStarted]. */
    fun install(view: WebView) {
        webView = view
        originalRendererPriority = view.rendererRequestedPriority to view.rendererPriorityWaivedWhenNotVisible
        if (!receiverRegistered) {
            createChannels(activity)
            ContextCompat.registerReceiver(
                activity,
                answerReceiver,
                IntentFilter(ACTION_ANSWER).apply { addDataScheme(ANSWER_SCHEME) },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching {
            WebViewCompat.addWebMessageListener(view, bridgeName, setOf("*")) { _, message, origin, isMainFrame, proxy ->
                activity.runOnUiThread { onBridgeMessage(message.data, origin, isMainFrame, proxy) }
            }
        }
    }

    fun onVisibilityChanged(visible: Boolean) {
        dispatch(WebappInput.Visibility(visible))
        // A tapped or swiped alert leaves the tray without telling the app; drop a summary left empty.
        if (visible) {
            handler.removeCallbacks(groupSweep)
            handler.postDelayed(groupSweep, GROUP_SWEEP_DELAY_MS)
        }
    }

    /** The main frame started a new document; the old one and its sockets are gone. */
    fun onDocumentStarted() {
        replyProxy = null
        proxyDoc = null
        dispatch(WebappInput.DocumentGone)
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

    /** Withdraws prompts (their socket dies with this WebView), stops keep-alive and unregisters. */
    fun dispose() {
        onDocumentStarted()
        handler.removeCallbacks(deferredStop)
        handler.removeCallbacks(groupSweep)
        stopKeepAliveService()
        if (receiverRegistered) runCatching { activity.unregisterReceiver(answerReceiver) }
        receiverRegistered = false
        webView = null
    }

    private fun onBridgeMessage(data: String?, origin: Uri, isMainFrame: Boolean, proxy: JavaScriptReplyProxy) {
        if (!isTrustedSource(origin, isMainFrame)) return
        val input = WebappBridgeEnvelope.parse(data) ?: return
        dispatch(input)
        val doc = state.doc?.id
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
        } finally {
            trayChanges.clear()
            dispatching = false
        }
    }

    private fun execute(effect: WebappEffect) {
        when (effect) {
            is WebappEffect.Post -> notify(effect.alert)
            is WebappEffect.Cancel -> {
                val id = effect.kind.notificationId(effect.key)
                NotificationManagerCompat.from(activity).cancel(GatewayAlertKind.ALERT_TAG, id)
                trayChanges[id] = null
            }
            is WebappEffect.Send -> send(effect)
            is WebappEffect.KeepAlive -> keepAlive(effect.on)
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

    private fun send(effect: WebappEffect.Send) {
        val proxy = replyProxy.takeIf { proxyDoc == effect.doc }
        val message = JSONObject()
            .put("t", "send")
            .put("doc", effect.doc.value)
            .put("sock", effect.sock)
            .put("key", effect.prompt.encode())
            .put("frame", effect.frame)
            .toString()
        val sent = proxy != null && runCatching { proxy.postMessage(message) }.isSuccess
        if (!sent) dispatch(WebappInput.SendResult(effect.doc, effect.prompt, ok = false))
    }

    private fun keepAlive(on: Boolean) {
        handler.removeCallbacks(deferredStop)
        if (on) {
            if (!keepAliveRunning) keepAliveRunning = HermesKeepAliveService.start(activity)
            watchNetwork()
        } else if (state.visible) {
            stopKeepAliveService()
        } else {
            // Hidden, but the document went away (a reload in the background). A new document
            // usually follows within seconds, and a hidden app may not be allowed to start again.
            handler.postDelayed(deferredStop, KEEP_ALIVE_STOP_GRACE_MS)
        }
        val view = webView ?: return
        val (priority, waived) = originalRendererPriority ?: return
        if (on) {
            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        } else {
            view.setRendererPriorityPolicy(priority, waived)
        }
    }

    private fun stopKeepAliveService() {
        keepAliveRunning = false
        HermesKeepAliveService.stop(activity)
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

    /** Default-network callback, live only while keep-alive runs. Callbacks arrive on [handler]. */
    private inner class NetworkWatch(initial: Network?) : ConnectivityManager.NetworkCallback() {
        private val identity = NetworkIdentity(initial)
        private var latest: Network? = initial
        private val settle = Runnable { if (identity.settle(latest)) nudgeOnline() }

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

    @SuppressLint("MissingPermission")
    private fun notify(alert: Alert) {
        if (!HermesKeepAliveService.canPostNotifications(activity)) return
        val headline = alert.kind.label
        val publicVersion = NotificationCompat.Builder(activity, alert.kind.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(activity.getString(R.string.app_name))
            .setContentText(headline)
            .build()
        val builder = NotificationCompat.Builder(activity, alert.kind.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(activity, R.color.brand_sky))
            .setContentTitle(alert.title)
            .setContentIntent(
                HermesKeepAliveService.openAppIntent(activity, alert.sessionId, alert.kind.notificationId(alert.key))
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
        val id = alert.kind.notificationId(alert.key)
        val posted = runCatching {
            NotificationManagerCompat.from(activity).notify(GatewayAlertKind.ALERT_TAG, id, builder.build())
        }.isSuccess
        if (posted) {
            trayChanges[id] = ShownAlert(
                id, AlertGroups.key(alert.sessionId), alert.title, alert.kind.channelId, System.currentTimeMillis()
            )
        }
    }

    /**
     * Posts or withdraws per-session summaries. The tray is read back from Android, overlaid with this
     * dispatch's own posts and cancels, which Android may not have applied yet.
     */
    private fun refreshGroups(changes: Map<Int, ShownAlert?>) {
        if (!HermesKeepAliveService.canPostNotifications(activity)) return
        val active = runCatching {
            activity.getSystemService(NotificationManager::class.java)?.activeNotifications?.toList()
        }.getOrNull().orEmpty()
        val shown = active.filter { it.tag == GatewayAlertKind.ALERT_TAG }.mapNotNull { it.toShownAlert() }.associateBy { it.id }
        val touched = changes.mapNotNullTo(mutableSetOf()) { (id, alert) -> (alert ?: shown[id])?.group }
        val summarized = active.filter { it.tag == SUMMARY_TAG }.mapNotNullTo(mutableSetOf()) { it.notification.group }
        val plan = AlertGroups.plan((shown + changes).values.filterNotNull(), summarized, touched)
        plan.cancel.forEach { group -> NotificationManagerCompat.from(activity).cancel(SUMMARY_TAG, AlertGroups.summaryId(group)) }
        plan.post.forEach(::notifySummary)
    }

    @SuppressLint("MissingPermission")
    private fun notifySummary(summary: GroupSummary) {
        val text = "${summary.count} updates"
        val publicVersion = NotificationCompat.Builder(activity, summary.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(activity.getString(R.string.app_name))
            .setContentText(text)
            .build()
        val style = NotificationCompat.InboxStyle().setSummaryText(text)
        summary.lines.forEach(style::addLine)
        val notification = NotificationCompat.Builder(activity, summary.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(activity, R.color.brand_sky))
            .setContentTitle(activity.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(style)
            .setGroup(summary.group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                HermesKeepAliveService.openAppIntent(activity, summary.sessionId, AlertGroups.summaryId(summary.group))
            )
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        runCatching {
            NotificationManagerCompat.from(activity).notify(SUMMARY_TAG, AlertGroups.summaryId(summary.group), notification)
        }
    }

    private fun decisionIntent(target: AnswerTarget, decision: Decision): PendingIntent =
        PendingIntent.getBroadcast(
            activity, 0,
            answerIntent(target, decision.name).putExtra(EXTRA_DECISION, decision.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun choiceIntent(target: AnswerTarget, index: Int): PendingIntent =
        PendingIntent.getBroadcast(
            activity, 0,
            answerIntent(target, "choice-$index").putExtra(EXTRA_CHOICE, index),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** The only mutable answer intent: Android writes the typed text into it. It stays package-scoped. */
    private fun replyIntent(target: AnswerTarget): PendingIntent {
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            activity, 0, answerIntent(target, "reply"), PendingIntent.FLAG_UPDATE_CURRENT or mutable
        )
    }

    private fun answerIntent(target: AnswerTarget, slot: String): Intent =
        Intent(ACTION_ANSWER)
            .setPackage(activity.packageName)
            // Distinct data per prompt and action keeps Android from reusing another prompt's PendingIntent.
            .setData(
                Uri.Builder().scheme(ANSWER_SCHEME).authority(target.doc.value)
                    .appendPath(target.prompt.encode()).appendPath(slot).build()
            )
            .putExtra(EXTRA_DOC, target.doc.value)
            .putExtra(EXTRA_PROMPT, target.prompt.encode())

    companion object {
        const val ACTION_ANSWER = "com.hermeswebui.android.action.WEBAPP_ANSWER"
        private const val ANSWER_SCHEME = "hermes-webapp-answer"
        private const val EXTRA_DOC = "com.hermeswebui.android.extra.WEBAPP_DOC"
        private const val EXTRA_PROMPT = "com.hermeswebui.android.extra.WEBAPP_PROMPT"
        private const val EXTRA_DECISION = "com.hermeswebui.android.extra.WEBAPP_DECISION"
        private const val EXTRA_CHOICE = "com.hermeswebui.android.extra.WEBAPP_CHOICE"
        private const val KEY_REPLY = "com.hermeswebui.android.extra.WEBAPP_REPLY"
        private const val KEEP_ALIVE_STOP_GRACE_MS = 15_000L
        private const val NETWORK_SETTLE_MS = 1_000L
        private const val MAX_SESSION_ID = 256
        private const val GROUP_SWEEP_DELAY_MS = 1_000L
        private const val SUMMARY_TAG = "hermes.webapp.summary"

        private fun StatusBarNotification.toShownAlert(): ShownAlert? {
            val group = notification.group ?: return null
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            return ShownAlert(id, group, title, notification.channelId, postTime)
        }

        /** One channel per [GatewayAlertKind] plus the keep-alive channel, grouped as "Hermes Agent". */
        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannelGroup(
                NotificationChannelGroup(HermesKeepAliveService.CHANNEL_GROUP_ID, "Hermes Agent")
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
            is WebappInput.Hello -> doc
            is WebappInput.Inbound -> doc
            is WebappInput.Responded -> doc
            is WebappInput.SocketClosed -> doc
            is WebappInput.SendResult -> doc
            else -> null
        }
    }
}
