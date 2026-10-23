package app.morsel.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderKeyTest {
    @Test
    fun keysAreRecognisedPerService() {
        assertTrue(Provider.DEEPSEEK.looksLikeKey("sk-FAKEtestDEEPSEEKkey0001"))
        assertTrue(Provider.OPENAI.looksLikeKey("sk-proj-FAKEtestOPENAIkey0001"))
        assertTrue(Provider.ANTHROPIC.looksLikeKey("sk-ant-FAKEtestCLAUDEkey0001"))
        assertTrue(Provider.OPENROUTER.looksLikeKey("sk-or-FAKEtestROUTERkey0001"))
        assertTrue(Provider.GEMINI.looksLikeKey("AIzaFAKEtestGEMINIkey0000001"))
        assertTrue(Provider.CUSTOM.looksLikeKey("gsk_FAKEtestCUSTOMkey0001"))
    }

    @Test
    fun otherServicesKeysAreNotTakenForThisOne() {
        assertFalse(Provider.DEEPSEEK.looksLikeKey("sk-ant-FAKEtestCLAUDEkey0001"))
        assertFalse(Provider.OPENAI.looksLikeKey("sk-or-FAKEtestROUTERkey0001"))
        assertFalse(Provider.ANTHROPIC.looksLikeKey("sk-FAKEtestDEEPSEEKkey0001"))
        assertFalse(Provider.GEMINI.looksLikeKey("sk-FAKEtestDEEPSEEKkey0001"))
    }

    @Test
    fun textThatIsNotAKeyIsIgnored() {
        assertFalse(Provider.DEEPSEEK.looksLikeKey("https://platform.deepseek.com/api_keys"))
        assertFalse(Provider.DEEPSEEK.looksLikeKey("sk-short"))
        assertFalse(Provider.DEEPSEEK.looksLikeKey("sk-with spaces in it 0123456789"))
        assertFalse(Provider.DEEPSEEK.looksLikeKey(""))
    }

    @Test
    fun surroundingWhitespaceIsIgnoredWhenChecking() {
        assertTrue(Provider.DEEPSEEK.looksLikeKey("  sk-FAKEtestDEEPSEEKkey0001 \n"))
    }

    @Test
    fun everyServiceExceptCustomHasAKeyPage() {
        Provider.entries.forEach { provider ->
            val url = provider.keyUrl
            if (provider == Provider.CUSTOM) {
                assertNull(url)
            } else {
                assertTrue(provider.name, url!!.startsWith("https://"))
            }
        }
    }
}
