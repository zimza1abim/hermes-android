package com.hermeswebui.android.background

import android.content.Context
import android.webkit.CookieManager
import com.hermeswebui.android.ui.MainUiState

class HermesForegroundServiceCoordinator(
    private val context: Context,
    private val isTrustedNotificationTarget: (String) -> Boolean,
    private val onCancelAutoRetry: () -> Unit
) {
    private var reconnectServiceRequest: ReconnectServiceRequest? = null

    fun onUiStateChanged(state: MainUiState, activityVisible: Boolean) {
        syncReconnectForegroundService(state, activityVisible)
    }

    fun onActivityResumed() {
        stopReconnectForegroundService()
    }

    fun onActivityStopped(state: MainUiState, activityVisible: Boolean) {
        syncReconnectForegroundService(state, activityVisible)
        if (
            ReconnectBackgroundPolicy.shouldCancelAutoRetryOnStop(
                backgroundReconnectEnabled = state.backgroundReconnectEnabled,
                activityVisible = activityVisible,
                isReconnecting = state.isReconnecting
            )
        ) {
            onCancelAutoRetry()
        }
    }

    private fun syncReconnectForegroundService(state: MainUiState, activityVisible: Boolean) {
        val sessionTargetUrl = state.currentUrl.takeIf(isTrustedNotificationTarget)
        val sessionId = ReconnectSessionStreamSupport.sessionIdFromUrl(sessionTargetUrl)
        if (
            !ReconnectBackgroundPolicy.shouldRunForegroundService(
                backgroundReconnectEnabled = state.backgroundReconnectEnabled,
                activityVisible = activityVisible,
                isReconnecting = state.isReconnecting,
                sseTransportEnabled = state.sseTransportEnabled,
                hasSessionId = sessionId != null
            )
        ) {
            stopReconnectForegroundService()
            return
        }
        val request = ReconnectServiceRequest(
            pollIntervalSeconds = state.reconnectPollIntervalSeconds,
            serverUrl = state.settings.serverUrl,
            sessionId = sessionId,
            sessionTargetUrl = sessionTargetUrl,
            cookieHeader = CookieManager.getInstance().getCookie(state.settings.serverUrl),
            sseTransportEnabled = state.sseTransportEnabled,
            isReconnecting = state.isReconnecting,
            showFullTextOnLockScreen = state.backgroundActivityFullTextEnabled
        )
        if (reconnectServiceRequest == request) return

        try {
            HermesReconnectService.start(
                context,
                pollIntervalSeconds = request.pollIntervalSeconds,
                serverUrl = request.serverUrl,
                sessionId = request.sessionId,
                sessionTargetUrl = request.sessionTargetUrl,
                cookieHeader = request.cookieHeader,
                sseTransportEnabled = request.sseTransportEnabled,
                isReconnecting = request.isReconnecting,
                showFullTextOnLockScreen = request.showFullTextOnLockScreen
            )
            reconnectServiceRequest = request
        } catch (_: IllegalStateException) {
            reconnectServiceRequest = null
            onCancelAutoRetry()
        } catch (_: SecurityException) {
            reconnectServiceRequest = null
            onCancelAutoRetry()
        }
    }

    private fun stopReconnectForegroundService() {
        if (reconnectServiceRequest == null) return
        HermesReconnectService.stop(context)
        reconnectServiceRequest = null
    }

    private data class ReconnectServiceRequest(
        val pollIntervalSeconds: Int,
        val serverUrl: String,
        val sessionId: String?,
        val sessionTargetUrl: String?,
        val cookieHeader: String?,
        val sseTransportEnabled: Boolean,
        val isReconnecting: Boolean,
        val showFullTextOnLockScreen: Boolean
    )
}
