package app.chompass.services.ai

import android.app.Application
import androidx.datastore.preferences.core.edit
import app.chompass.data.Keys
import app.chompass.data.PreferencesStore
import app.chompass.models.AIProvider
import app.chompass.models.AiFeature
import app.chompass.models.AiProfile
import app.chompass.models.ServingUnitInferenceMode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/**
 * End-to-end routing through [FoodAnalysisService.dispatch]: a photo request
 * must leave on the Food-photos profile and a typed meal on the Food-text
 * profile, each with its own URL, model, and key — while the active profile
 * stays untouched. This is the wiring the settings sheet configures, exercised
 * against two real (mock) endpoints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PerFeatureRoutingDispatchTest {
    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer
    private lateinit var prefs: PreferencesStore

    private lateinit var profileA: AiProfile
    private lateinit var profileB: AiProfile

    private fun chunk(text: String, finishReason: String): String {
        val delta = JSONObject().put("content", text)
        val choice = JSONObject().put("delta", delta).put("finish_reason", finishReason)
        return "data: " + JSONObject().put("choices", JSONArray().put(choice)) + "\n\n"
    }

    private fun mealJson(): String = """
        {"name":"Chicken bowl","calories":540,"protein":42.0,"carbs":60.0,"fat":0.0,
        "serving_size_grams":400.0,"emoji":"🍲","fiber":4.0,
        "constituents":[{"name":"chicken and rice","calories":540,"protein":42.0,"carbs":60.0,"fat":0.0,
        "serving_size_grams":400.0}],"unit_options":[]}
    """.trimIndent().replace("\n", "")

    private fun bodyOf(request: okhttp3.mockwebserver.RecordedRequest): JSONObject =
        JSONObject(request.body.readUtf8())

    private fun newService(): FoodAnalysisService = FoodAnalysisService(
        prefs = prefs,
        okHttp = OkHttpClient(),
        keyLookup = { profile -> if (profile.id == profileB.id) "key-b" else "key-a" },
        inferenceModeForTest = ServingUnitInferenceMode.GRAMS_ONLY,
    )

    @Before
    fun setUp() {
        serverA = MockWebServer()
        serverB = MockWebServer()
        serverA.start()
        serverB.start()
        val context = RuntimeEnvironment.getApplication()
        prefs = PreferencesStore(context)
        runBlocking {
            prefs.dataStore.edit {
                it.remove(Keys.AI_PROFILES_JSON)
                it.remove(Keys.ACTIVE_AI_PROFILE_ID)
                it.remove(Keys.FALLBACK_AI_PROFILE_ID)
                AiFeature.entries.forEach { feature -> it.remove(Keys.featureAiProfileId(feature)) }
            }
            profileA = AiProfile(
                id = UUID.randomUUID(),
                name = "Active A",
                provider = AIProvider.OPENROUTER,
                model = "model-a",
                baseUrl = serverA.url("/v1").toString(),
                createdAt = Instant.now(),
            )
            profileB = AiProfile(
                id = UUID.randomUUID(),
                name = "Routed B",
                provider = AIProvider.OPENROUTER,
                model = "model-b",
                baseUrl = serverB.url("/v1").toString(),
                createdAt = Instant.now(),
            )
            prefs.mutateProfiles {
                it += profileA
                it += profileB
            }
            prefs.setActiveAiProfileId(profileA.id.toString())
            prefs.setFallbackAiProfileId(null)
            prefs.setFallbackEnabled(false)
            prefs.setMealConstituentsEnabled(true)
        }
    }

    @After
    fun tearDown() {
        serverA.shutdown()
        serverB.shutdown()
    }

    @Test
    fun photoCall_leavesOnTheFoodPhotoProfile() = runBlocking {
        prefs.setFeatureAiProfileId(AiFeature.FOOD_PHOTO, profileB.id.toString())
        serverB.enqueue(MockResponse().setBody(chunk(mealJson(), "stop")))

        val result = newService().analyzeFood(byteArrayOf(1, 2, 3, 4)) { }

        assertEquals(540, result.calories)
        assertEquals("the active profile must not be touched", 0, serverA.requestCount)
        assertEquals(1, serverB.requestCount)
        val request = serverB.takeRequest()
        assertEquals("Bearer key-b", request.getHeader("Authorization"))
        assertEquals("model-b", bodyOf(request).getString("model"))
    }

    @Test
    fun textCall_leavesOnTheFoodTextProfileWhilePhotosStayActive() = runBlocking {
        prefs.setFeatureAiProfileId(AiFeature.FOOD_TEXT, profileB.id.toString())
        serverB.enqueue(MockResponse().setBody(chunk(mealJson(), "stop")))
        serverA.enqueue(MockResponse().setBody(chunk(mealJson(), "stop")))

        val text = newService().analyzeText("chicken bowl") { }
        assertEquals(540, text.calories)
        assertEquals(1, serverB.requestCount)
        assertEquals("text routing must not reach the active profile", 0, serverA.requestCount)

        val photo = newService().analyzeFood(byteArrayOf(1, 2, 3, 4)) { }
        assertEquals(540, photo.calories)
        // The photo went to the still-active profile A, not the text-routed B.
        assertEquals(1, serverA.requestCount)
        assertEquals(1, serverB.requestCount)
    }

    @Test
    fun unroutedFeatures_keepUsingTheActiveProfile() = runBlocking {
        serverA.enqueue(MockResponse().setBody(chunk(mealJson(), "stop")))

        val result = newService().analyzeFood(byteArrayOf(1, 2, 3, 4)) { }

        assertEquals(540, result.calories)
        assertEquals(1, serverA.requestCount)
        assertEquals(0, serverB.requestCount)
        assertEquals("model-a", bodyOf(serverA.takeRequest()).getString("model"))
    }
}
