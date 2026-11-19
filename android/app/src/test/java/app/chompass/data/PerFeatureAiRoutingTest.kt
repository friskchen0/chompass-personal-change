package app.chompass.data

import androidx.datastore.preferences.core.edit
import app.chompass.models.AIProvider
import app.chompass.models.AiFeature
import app.chompass.models.AiProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/**
 * Per-feature AI routing: every [AiFeature] slot resolves to an [AiProfile],
 * and an unset, blank, or stale slot resolves to the active profile so an
 * install that never opens the routing screen keeps the single-profile
 * behavior. The explicit call-site override (queue "Run with…") still wins
 * over the feature slot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class PerFeatureAiRoutingTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun resetStores() {
        // The DataStore is a per-process singleton that survives across test
        // methods: clear profiles, both routing ids, and every feature slot.
        runBlocking {
            prefs().dataStore.edit {
                it.remove(Keys.AI_PROFILES_JSON)
                it.remove(Keys.ACTIVE_AI_PROFILE_ID)
                it.remove(Keys.FALLBACK_AI_PROFILE_ID)
                it.remove(Keys.AI_READ_TIMEOUT_SECONDS)
                it.remove(Keys.GEMINI_GOOGLE_SEARCH_ENABLED)
                it.remove(Keys.OPENROUTER_REASONING_EFFORT)
                AiFeature.entries.forEach { feature -> it.remove(Keys.featureAiProfileId(feature)) }
            }
        }
    }

    private fun prefs() = PreferencesStore(context)

    /** Vision profile: what the user would point "Food photos" at. */
    private fun geminiProfile() = AiProfile(
        id = UUID.randomUUID(),
        name = "Gemini photos",
        provider = AIProvider.GEMINI,
        model = "gemini-3.5-flash-lite",
        visionModel = "gemini-3.5-flash-lite",
        createdAt = Instant.ofEpochMilli(1_700_000_000_000),
    )

    /** Text profile: DeepSeek is an OpenAI-compatible custom endpoint in Chompass. */
    private fun deepSeekProfile() = AiProfile(
        id = UUID.randomUUID(),
        name = "DeepSeek",
        provider = AIProvider.CUSTOM_OPENAI,
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-chat",
        createdAt = Instant.ofEpochMilli(1_700_000_100_000),
    )

    private suspend fun install(
        vararg profiles: AiProfile,
        active: AiProfile,
    ) {
        prefs().mutateProfiles { list -> profiles.forEach { list += it } }
        prefs().setActiveAiProfileId(active.id.toString())
    }

    @Test
    fun noSlotSet_everyFeatureFollowsTheActiveProfile() = runBlocking {
        val gemini = geminiProfile()
        val deepSeek = deepSeekProfile()
        install(gemini, deepSeek, active = gemini)

        AiFeature.entries.forEach { feature ->
            val cfg = prefs().aiCallConfig(feature = feature)
            assertEquals("feature $feature should follow the active profile", gemini.id, cfg.profile.id)
        }
    }

    @Test
    fun pinnedSlot_routesOnlyThatFeature() = runBlocking {
        val gemini = geminiProfile()
        val deepSeek = deepSeekProfile()
        install(gemini, deepSeek, active = gemini)
        prefs().setFeatureAiProfileId(AiFeature.COACH, deepSeek.id.toString())
        prefs().setFeatureAiProfileId(AiFeature.GOAL_RECALC, deepSeek.id.toString())

        // Gemini + version-pinned model for photos, DeepSeek for the text features.
        val photo = prefs().aiCallConfig(feature = AiFeature.FOOD_PHOTO)
        assertEquals(gemini.id, photo.profile.id)
        assertEquals(AIProvider.GEMINI, photo.provider)
        assertEquals("gemini-3.5-flash-lite", photo.selectedModel)

        val coach = prefs().aiCallConfig(feature = AiFeature.COACH)
        assertEquals(deepSeek.id, coach.profile.id)
        assertEquals(AIProvider.CUSTOM_OPENAI, coach.provider)
        assertEquals("deepseek-chat", coach.selectedModel)
        assertEquals("https://api.deepseek.com/v1", coach.customBaseUrl)

        val goal = prefs().aiCallConfig(feature = AiFeature.GOAL_RECALC)
        assertEquals(deepSeek.id, goal.profile.id)

        // An unpinned feature still follows the active profile.
        assertEquals(gemini.id, prefs().aiCallConfig(feature = AiFeature.FOOD_TEXT).profile.id)
    }

    @Test
    fun staleSlot_fallsBackToTheActiveProfile() = runBlocking {
        val gemini = geminiProfile()
        install(gemini, active = gemini)
        prefs().setFeatureAiProfileId(AiFeature.FOOD_PHOTO, UUID.randomUUID().toString())

        val cfg = prefs().aiCallConfig(feature = AiFeature.FOOD_PHOTO)
        assertEquals(gemini.id, cfg.profile.id)
    }

    @Test
    fun blankSlot_isTreatedAsUnset() = runBlocking {
        val gemini = geminiProfile()
        install(gemini, active = gemini)
        prefs().setFeatureAiProfileId(AiFeature.FOOD_PHOTO, "   ")

        assertEquals(gemini.id, prefs().aiCallConfig(feature = AiFeature.FOOD_PHOTO).profile.id)
        assertNull(prefs().featureAiProfileId(AiFeature.FOOD_PHOTO).first())
    }

    @Test
    fun explicitOverride_beatsTheFeatureSlot() = runBlocking {
        val gemini = geminiProfile()
        val deepSeek = deepSeekProfile()
        val third = geminiProfile().copy(name = "Third")
        install(gemini, deepSeek, third, active = gemini)
        prefs().setFeatureAiProfileId(AiFeature.FOOD_PHOTO, deepSeek.id.toString())

        // Queue "Run with…" passes a profile directly; routing must not hijack it.
        val cfg = prefs().aiCallConfig(profileOverride = third, feature = AiFeature.FOOD_PHOTO)
        assertEquals(third.id, cfg.profile.id)
    }

    @Test
    fun clearingTheSlot_returnsTheFeatureToTheActiveProfile() = runBlocking {
        val gemini = geminiProfile()
        val deepSeek = deepSeekProfile()
        install(gemini, deepSeek, active = gemini)
        prefs().setFeatureAiProfileId(AiFeature.COACH, deepSeek.id.toString())
        assertEquals(deepSeek.id, prefs().aiCallConfig(feature = AiFeature.COACH).profile.id)

        prefs().setFeatureAiProfileId(AiFeature.COACH, null)
        assertEquals(gemini.id, prefs().aiCallConfig(feature = AiFeature.COACH).profile.id)
    }

    @Test
    fun slotSurvivesANewStoreInstance() = runBlocking {
        val gemini = geminiProfile()
        val deepSeek = deepSeekProfile()
        install(gemini, deepSeek, active = gemini)
        prefs().setFeatureAiProfileId(AiFeature.COACH, deepSeek.id.toString())

        // A fresh store instance is what the next app start does.
        val afterRestart = prefs().aiCallConfig(feature = AiFeature.COACH)
        assertEquals(deepSeek.id, afterRestart.profile.id)
        assertEquals("deepseek-chat", afterRestart.selectedModel)
    }

    @Test
    fun featureProfileIdsFlow_exposesEverySlot() = runBlocking {
        val gemini = geminiProfile()
        val deepSeek = deepSeekProfile()
        install(gemini, deepSeek, active = gemini)
        prefs().setFeatureAiProfileId(AiFeature.FOOD_PHOTO, gemini.id.toString())
        prefs().setFeatureAiProfileId(AiFeature.FOOD_TEXT, deepSeek.id.toString())
        prefs().setFeatureAiProfileId(AiFeature.COACH, deepSeek.id.toString())
        prefs().setFeatureAiProfileId(AiFeature.GOAL_RECALC, deepSeek.id.toString())

        val ids = prefs().featureAiProfileIds.first()
        assertEquals(gemini.id.toString(), ids[AiFeature.FOOD_PHOTO])
        assertEquals(deepSeek.id.toString(), ids[AiFeature.FOOD_TEXT])
        assertEquals(deepSeek.id.toString(), ids[AiFeature.COACH])
        assertEquals(deepSeek.id.toString(), ids[AiFeature.GOAL_RECALC])
    }

    @Test
    fun fallbackProfile_isResolvedIndependentlyOfTheFeatureSlot() = runBlocking {
        val gemini = geminiProfile()
        val deepSeek = deepSeekProfile()
        install(gemini, deepSeek, active = gemini)
        prefs().setFeatureAiProfileId(AiFeature.FOOD_PHOTO, gemini.id.toString())
        prefs().setFallbackAiProfileId(deepSeek.id.toString())

        val cfg = prefs().aiCallConfig(feature = AiFeature.FOOD_PHOTO)
        assertEquals(gemini.id, cfg.profile.id)
        assertEquals(deepSeek.id, cfg.fallbackProfile?.id)
        // Role check, not just identity: 503 on Gemini should retire to DeepSeek.
        assertEquals(AIProvider.CUSTOM_OPENAI, cfg.fallbackProfile?.provider)
    }

    @Test
    fun fallbackEqualToTheRoutedProfile_isIgnored() = runBlocking {
        val gemini = geminiProfile()
        val deepSeek = deepSeekProfile()
        install(gemini, deepSeek, active = gemini)
        prefs().setFeatureAiProfileId(AiFeature.COACH, deepSeek.id.toString())
        prefs().setFallbackAiProfileId(deepSeek.id.toString())

        val cfg = prefs().aiCallConfig(feature = AiFeature.COACH)
        assertEquals(deepSeek.id, cfg.profile.id)
        assertNull("a fallback identical to the primary is a pointless retry", cfg.fallbackProfile)
    }

    @Test
    fun emptyStore_stillProducesAUsableConfig() = runBlocking {
        // No profiles at all: the in-memory Gemini stand-in keeps the screen alive
        // (pre-migration / freshly cleared store), exactly as before this feature.
        val cfg = prefs().aiCallConfig(feature = AiFeature.COACH)
        assertEquals(AIProvider.GEMINI, cfg.provider)
        assertNull(cfg.fallbackProfile)
    }
}
