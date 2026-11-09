package com.hermeswebui.android.webapp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetworkIdentityTest {
    @Test
    fun `the network present at registration is the baseline, not a change`() {
        assertThat(NetworkIdentity("wifi").settle("wifi")).isFalse()
    }

    @Test
    fun `switching to another network is a change once`() {
        val identity = NetworkIdentity("wifi")
        assertThat(identity.settle("cellular")).isTrue()
        assertThat(identity.settle("cellular")).isFalse()
    }

    @Test
    fun `losing the network with no replacement is not a change`() {
        assertThat(NetworkIdentity("wifi").settle(null)).isFalse()
    }

    @Test
    fun `regaining a network after a settled loss is a change, even the same one`() {
        val identity = NetworkIdentity("wifi")
        identity.settle(null)
        assertThat(identity.settle("wifi")).isTrue()
    }

    @Test
    fun `a first network after starting with none is a change`() {
        assertThat(NetworkIdentity<String>(null).settle("wifi")).isTrue()
    }
}
