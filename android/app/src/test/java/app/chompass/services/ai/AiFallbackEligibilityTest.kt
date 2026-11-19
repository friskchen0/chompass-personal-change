package app.chompass.services.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fallback gating: failures that are local to the device or to the request
 * shape are not worth a second call on the fallback profile, because they fail
 * the same way everywhere. Provider-side failures — including the 400s Gemini
 * uses for region blocks and unavailable models, and on-device low-memory /
 * model-missing errors — stay eligible so the fallback can rescue them.
 */
class AiFallbackEligibilityTest {

    private fun api(status: Int?) = AiError.Api("HTTP $status", httpStatus = status)

    @Test
    fun providerSideFailures_areEligible() {
        // The case that started this: Gemini 503 during a normal day.
        assertThat(isFallbackEligible(api(503))).isTrue()
        assertThat(isFallbackEligible(api(529))).isTrue()
        assertThat(isFallbackEligible(api(500))).isTrue()
        assertThat(isFallbackEligible(api(502))).isTrue()
        assertThat(isFallbackEligible(api(504))).isTrue()
        assertThat(isFallbackEligible(api(429))).isTrue()
        assertThat(isFallbackEligible(api(408))).isTrue()
        assertThat(isFallbackEligible(AiError.Timeout)).isTrue()
        assertThat(isFallbackEligible(AiError.Network(java.io.IOException("reset")))).isTrue()
        assertThat(isFallbackEligible(AiError.InvalidResponse)).isTrue()
        assertThat(isFallbackEligible(AiError.ResponseTruncated)).isTrue()
        // Unknown transport-level throwables stay eligible: they were retried before.
        assertThat(isFallbackEligible(java.io.IOException("socket closed"))).isTrue()
    }

    @Test
    fun geminiStyleBadRequests_stayEligible() {
        // Gemini reports a region block and an unavailable model as 400, and a
        // wrong model as 404 — another provider genuinely can serve these.
        assertThat(isFallbackEligible(api(400))).isTrue()
        assertThat(isFallbackEligible(api(404))).isTrue()
        assertThat(isFallbackEligible(api(422))).isTrue()
        assertThat(isFallbackEligible(AiError.Api("garbage body"))).isTrue()
    }

    @Test
    fun onDeviceFailures_stayEligible() {
        // These are exactly what the fallback exists for: a smaller model, or a
        // cloud profile when the on-device model cannot run.
        assertThat(isFallbackEligible(AiError.OnDeviceLowMemory)).isTrue()
        assertThat(isFallbackEligible(AiError.OnDeviceModelNotDownloaded)).isTrue()
        assertThat(isFallbackEligible(AiError.OnDeviceUnsupportedDevice)).isTrue()
        assertThat(isFallbackEligible(AiError.OnDeviceEngineInit(RuntimeException("gpu")))).isTrue()
    }

    @Test
    fun missingKey_staysEligible() {
        // The active profile may have no key while the fallback profile has one.
        assertThat(isFallbackEligible(AiError.NoApiKey)).isTrue()
    }

    @Test
    fun rejectedKey_isNotEligible() {
        // A dead key must surface: switching providers would hide the fix.
        assertThat(isFallbackEligible(api(401))).isFalse()
        assertThat(isFallbackEligible(api(403))).isFalse()
    }

    @Test
    fun localConfigurationErrors_areNotEligible() {
        assertThat(isFallbackEligible(AiError.Disabled)).isFalse()
        assertThat(isFallbackEligible(AiError.InvalidUrl("nope"))).isFalse()
        assertThat(isFallbackEligible(AiError.InsecureHttpBlocked)).isFalse()
        assertThat(isFallbackEligible(AiError.ImageConversionFailed)).isFalse()
    }
}
