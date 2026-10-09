package app.clearmind.companion

import org.junit.Assert.*
import org.junit.Test

class PairingInputTest {
    @Test fun acceptsParentBackupCode() { assertEquals("CLM-123456", PairingInput.parse(" clm-123456 ")); assertNull(PairingInput.parse("12345")) }
    @Test fun acceptsParentSetupQr() {
        val token = "abcdefghjkmnpqrstuvwxyz23456789ab"
        assertEquals(token, PairingInput.parse("${BuildConfig.API_BASE}/setup-child?token=$token&code=CLM-123456"))
    }
    @Test fun rejectsForeignQrOrigins() { assertNull(PairingInput.parse("https://evil.example/pair/abcdefghjkmnpqrstuvwxyz23456789ab")) }
    @Test fun rejectsInsecureQr() { assertNull(PairingInput.parse(BuildConfig.API_BASE.replace("https:", "http:") + "/pair/abcdefghjkmnpqrstuvwxyz23456789ab")) }
}