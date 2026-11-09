package com.hermeswebui.android.webapp

/**
 * The default network as last settled after debouncing. [settle] reports a change only when a
 * different, non-null network became the default: a flap back to the same network or a loss with
 * no replacement is not worth waking the page for.
 */
internal class NetworkIdentity<T : Any>(initial: T?) {
    private var settled: T? = initial

    fun settle(current: T?): Boolean {
        val changed = current != null && current != settled
        settled = current
        return changed
    }
}
