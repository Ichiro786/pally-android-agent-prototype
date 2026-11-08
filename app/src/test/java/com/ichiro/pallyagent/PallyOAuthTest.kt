package com.ichiro.pallyagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PallyOAuthTest {
    @Test fun pkceUsesRfc7636S256UrlEncoding() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", PallyOAuth.pkceChallenge(verifier))
    }

    @Test fun callbackMustMatchExactPrivateSchemeHostAndPath() {
        assertTrue(PallyOAuth.isRedirectUri("pallyagent://oauth/callback?state=s&code=c"))
        assertFalse(PallyOAuth.isRedirectUri("https://oauth/callback?state=s&code=c"))
        assertFalse(PallyOAuth.isRedirectUri("pallyagent://evil/callback?state=s&code=c"))
        assertFalse(PallyOAuth.isRedirectUri("pallyagent://oauth/other?state=s&code=c"))
        assertFalse(PallyOAuth.isRedirectUri("pallyagent://oauth@evil.example/callback?state=s&code=c"))
    }

    @Test fun comparesOAuthStateExactly() {
        assertTrue(PallyOAuth.stateMatches("state-1", "state-1"))
        assertFalse(PallyOAuth.stateMatches("state-1", "state-2"))
    }
}
