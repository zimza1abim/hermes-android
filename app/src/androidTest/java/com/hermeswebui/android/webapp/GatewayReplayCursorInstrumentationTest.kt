package com.hermeswebui.android.webapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GatewayReplayCursorInstrumentationTest {
    @Test
    fun clearingOnForegroundDiscardsOnlyTheSelectedServersSavedCursor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("hermes_monitor_cursors", Context.MODE_PRIVATE)
        val url = "https://replay-test.invalid"
        val other = "https://replay-other.invalid"
        fun key(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        prefs.edit().putString(key(url), "saved").putString(key(other), "other").commit()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                GatewayMonitorLink(context, Handler(Looper.getMainLooper())) {}.clearReplayCursors(url)
            }
            assertThat(prefs.contains(key(url))).isFalse()
            assertThat(prefs.getString(key(other), null)).isEqualTo("other")
        } finally {
            prefs.edit().remove(key(url)).remove(key(other)).commit()
        }
    }
}
