package com.project01.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM — no Android networking involved. */
class HostAddressResolverTest {

    @Test
    fun `formatDhcpGateway decodes the little-endian int a hotspot reports`() {
        // 192.168.43.1 little-endian: 192 | 168<<8 | 43<<16 | 1<<24
        val gateway = 192 or (168 shl 8) or (43 shl 16) or (1 shl 24)
        assertEquals("192.168.43.1", HostAddressResolver.formatDhcpGateway(gateway))
    }

    @Test
    fun `formatDhcpGateway returns null for the framework's unknown value`() {
        // 0 means "no DHCP info" — must fall through, not report 0.0.0.0 as the host.
        assertNull(HostAddressResolver.formatDhcpGateway(0))
    }

    @Test
    fun `isUsableHost rejects blank, wildcard and loopback addresses`() {
        assertFalse(HostAddressResolver.isUsableHost(null))
        assertFalse(HostAddressResolver.isUsableHost(""))
        assertFalse(HostAddressResolver.isUsableHost("0.0.0.0"))
        assertFalse("connecting to loopback would dial ourselves",
            HostAddressResolver.isUsableHost("127.0.0.1"))
    }

    @Test
    fun `isUsableHost accepts a real gateway`() {
        assertTrue(HostAddressResolver.isUsableHost("192.168.43.1"))
        assertTrue(HostAddressResolver.isUsableHost("192.168.1.1"))
    }
}
