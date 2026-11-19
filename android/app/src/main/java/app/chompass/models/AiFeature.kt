package app.chompass.models

import androidx.annotation.StringRes
import app.chompass.R

/**
 * Per-feature AI routing slots: "photos with Gemini, Coach with DeepSeek".
 *
 * Each slot stores the id of an [AiProfile] in DataStore
 * ([app.chompass.data.Keys.featureAiProfileId]). An empty, blank, or stale slot
 * falls back to the active profile, so a user who never opens the routing
 * screen gets exactly the single-profile behavior of earlier builds.
 *
 * The slot is deliberately a *profile* id rather than a provider: a profile
 * already carries the provider, model, base URL, vision/coach/analysis model
 * slots and its own API key, so routing per feature reuses that machinery
 * instead of duplicating it.
 */
enum class AiFeature(
    /** Stable DataStore suffix. Never rename: persisted values reference it. */
    val storageKey: String,
) {
    /** Food photos and nutrition labels — image-bearing food analysis. */
    FOOD_PHOTO("food_photo"),

    /** Text-only food analysis: typed meals, fill/re-estimate, "What if?". */
    FOOD_TEXT("food_text"),

    /** AI Coach chat. */
    COACH("coach"),

    /** Daily calorie/macro target recalculation. */
    GOAL_RECALC("goal_recalc");

    @get:StringRes
    val displayNameRes: Int
        get() = when (this) {
            FOOD_PHOTO -> R.string.ai_feature_food_photo
            FOOD_TEXT -> R.string.ai_feature_food_text
            COACH -> R.string.ai_feature_coach
            GOAL_RECALC -> R.string.ai_feature_goal_recalc
        }

    @get:StringRes
    val descriptionRes: Int
        get() = when (this) {
            FOOD_PHOTO -> R.string.ai_feature_food_photo_desc
            FOOD_TEXT -> R.string.ai_feature_food_text_desc
            COACH -> R.string.ai_feature_coach_desc
            GOAL_RECALC -> R.string.ai_feature_goal_recalc_desc
        }

    companion object {
        fun fromStorageKey(raw: String?): AiFeature? = entries.firstOrNull { it.storageKey == raw }
    }
}
