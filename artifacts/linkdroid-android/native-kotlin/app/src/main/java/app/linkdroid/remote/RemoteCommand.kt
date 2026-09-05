package app.linkdroid.remote

import org.json.JSONObject

sealed class RemoteCommand {
    abstract val kind: String

    data class Tap(
        val x: Float,
        val y: Float,
        val durationMs: Long = 80L,
    ) : RemoteCommand() {
        override val kind = "tap"
    }

    data class Swipe(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long = 400L,
    ) : RemoteCommand() {
        override val kind = "swipe"
    }

    data class Text(val value: String) : RemoteCommand() {
        override val kind = "text"
    }

    data object Back : RemoteCommand() {
        override val kind = "back"
    }

    data object Home : RemoteCommand() {
        override val kind = "home"
    }

    fun toJson(): JSONObject = when (this) {
        is Tap -> JSONObject()
            .put("kind", kind)
            .put("x", x)
            .put("y", y)
            .put("durationMs", durationMs)
        is Swipe -> JSONObject()
            .put("kind", kind)
            .put("startX", startX)
            .put("startY", startY)
            .put("endX", endX)
            .put("endY", endY)
            .put("durationMs", durationMs)
        is Text -> JSONObject().put("kind", kind).put("value", value)
        Back -> JSONObject().put("kind", kind)
        Home -> JSONObject().put("kind", kind)
    }

    companion object {
        fun fromJson(json: JSONObject): RemoteCommand? {
            return when (json.optString("kind")) {
                "tap" -> Tap(
                    x = json.optDouble("x", -1.0).toFloat(),
                    y = json.optDouble("y", -1.0).toFloat(),
                    durationMs = json.optLong("durationMs", 80L),
                ).takeIf { it.x in 0f..1f && it.y in 0f..1f && it.durationMs in 1L..5_000L }
                "swipe" -> Swipe(
                    startX = json.optDouble("startX", -1.0).toFloat(),
                    startY = json.optDouble("startY", -1.0).toFloat(),
                    endX = json.optDouble("endX", -1.0).toFloat(),
                    endY = json.optDouble("endY", -1.0).toFloat(),
                    durationMs = json.optLong("durationMs", 400L),
                ).takeIf {
                    it.startX in 0f..1f && it.startY in 0f..1f &&
                        it.endX in 0f..1f && it.endY in 0f..1f &&
                        it.durationMs in 1L..5_000L
                }
                "text" -> Text(json.optString("value")).takeIf { it.value.length <= 1_000 }
                "back" -> Back
                "home" -> Home
                else -> null
            }
        }
    }
}