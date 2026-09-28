package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VinorBridgeGateTest {

    @Before
    fun clearPerViewUrls() {
        VinorBridgeGate.resetForTests()
    }

    @Test
    fun partnerHttps_staysInBridgedWebView() {
        assertTrue(
            VinorBridgeGate.navigationForBridgedWebView(
                "https://vinor.ir/express/partner/dashboard",
            ) is VinorBridgeGate.BridgedNav.Allow,
        )
        assertTrue(
            VinorBridgeGate.navigationForBridgedWebView(
                "https://www.vinor.ir/express/partner/login",
            ) is VinorBridgeGate.BridgedNav.Allow,
        )
        assertTrue(
            VinorBridgeGate.navigationForBridgedWebView(
                "https://vinor.ir/express/partner/",
            ) is VinorBridgeGate.BridgedNav.Allow,
        )
    }

    @Test
    fun cleartextVinor_upgradesToHttps() {
        val nav = VinorBridgeGate.navigationForBridgedWebView("http://vinor.ir/express/partner/login")
        assertTrue(nav is VinorBridgeGate.BridgedNav.Upgrade)
        assertEquals(
            "https://vinor.ir/express/partner/login",
            (nav as VinorBridgeGate.BridgedNav.Upgrade).url,
        )
    }

    @Test
    fun externalAndLookalike_leaveOrBlock() {
        assertTrue(
            VinorBridgeGate.navigationForBridgedWebView("https://evil.example/phish")
                is VinorBridgeGate.BridgedNav.OpenExternal,
        )
        assertTrue(
            VinorBridgeGate.navigationForBridgedWebView("http://evil.example/phish")
                is VinorBridgeGate.BridgedNav.OpenExternal,
        )
        assertTrue(
            VinorBridgeGate.navigationForBridgedWebView("https://vinor.ir.evil.com/")
                is VinorBridgeGate.BridgedNav.OpenExternal,
        )
        assertTrue(
            VinorBridgeGate.navigationForBridgedWebView("javascript:alert(1)")
                is VinorBridgeGate.BridgedNav.Block,
        )
        assertTrue(
            VinorBridgeGate.navigationForBridgedWebView("data:text/html,<script>1</script>")
                is VinorBridgeGate.BridgedNav.Block,
        )
        assertTrue(
            VinorBridgeGate.navigationForBridgedWebView("file:///sdcard/x.html")
                is VinorBridgeGate.BridgedNav.Block,
        )
        assertTrue(VinorBridgeGate.navigationForBridgedWebView("  ") is VinorBridgeGate.BridgedNav.Block)
    }

    @Test
    fun perWebViewUrl_doesNotCrossContaminate() {
        val partner = Any()
        val other = Any()
        VinorBridgeGate.remember(partner, "https://vinor.ir/express/partner/")
        VinorBridgeGate.remember(other, "https://evil.example/")
        assertTrue(VinorBridgeGate.isTrustedFor(partner, liveUrl = null, liveReadable = false))
        assertFalse(VinorBridgeGate.isTrustedFor(other, liveUrl = null, liveReadable = false))
        assertFalse(VinorBridgeGate.isTrustedFor(Any(), liveUrl = null, liveReadable = false))
        assertFalse(VinorBridgeGate.isTrustedFor(null, "https://vinor.ir/", liveReadable = true))
    }

    @Test
    fun liveUntrustedUrl_overridesRememberedTrust() {
        val view = Any()
        VinorBridgeGate.remember(view, "https://vinor.ir/express/partner/")
        assertFalse(VinorBridgeGate.isTrustedFor(view, "https://evil.example/", liveReadable = true))
        assertTrue(VinorBridgeGate.isTrustedFor(view, "about:blank", liveReadable = true))
        assertFalse(VinorBridgeGate.isTrustedFor(view, "data:text/html,x", liveReadable = true))
        assertTrue(VinorBridgeGate.isTrustedFor(view, "", liveReadable = true))
        assertTrue(VinorBridgeGate.isTrustedFor(view, null, liveReadable = false))
    }

    @Test
    fun placeholder_doesNotClobberPerViewTrust_untrustedUrlDoes() {
        val view = Any()
        VinorBridgeGate.remember(view, "https://vinor.ir/help")
        VinorBridgeGate.remember(view, "about:blank")
        assertTrue(VinorBridgeGate.isTrustedFor(view, liveUrl = null, liveReadable = false))
        VinorBridgeGate.remember(view, "https://evil.example/x")
        assertFalse(VinorBridgeGate.isTrustedFor(view, liveUrl = null, liveReadable = false))
    }

    @Test
    fun schemesAndEmpty_areNotTrustedUrls() {
        assertFalse(VinorBridgeGate.isTrustedUrl(null))
        assertFalse(VinorBridgeGate.isTrustedUrl("about:blank"))
        assertFalse(VinorBridgeGate.isTrustedUrl("chrome://crash"))
        assertFalse(VinorBridgeGate.isTrustedUrl("blob:https://vinor.ir/uuid"))
        assertTrue(VinorBridgeGate.isTrustedUrl("https://vinor.ir/express/partner/notifications"))
    }
}
