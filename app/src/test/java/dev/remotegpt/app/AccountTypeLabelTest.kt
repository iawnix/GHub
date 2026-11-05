package dev.remotegpt.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountTypeLabelTest {
    @Test fun knownProvidersReadAsProductNamesAndUnknownOnesPassThrough() {
        assertEquals("ChatGPT", accountTypeLabel("chatgpt"))
        assertEquals("customProvider", accountTypeLabel("customProvider"))
    }
}
