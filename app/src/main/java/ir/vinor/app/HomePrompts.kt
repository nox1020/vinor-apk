package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/** بنر بازاریاب از خانه برداشته شده و در «وینور من» است. */
object HomePrompts {
    fun isAdvisorBanner(prompt: JSONObject): Boolean {
        val href = prompt.optString("href")
        val label = prompt.optString("label")
        if (href.contains("/apply") || href.contains("/thanks")) return true
        if (label.contains("بازاریاب")) return true
        return prompt.optString("variant") == "hero" && label == VinorManMenu.ADVISOR_STATUS_LABEL
    }

    fun withoutAdvisorBanner(prompts: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until prompts.length()) {
            val prompt = prompts.optJSONObject(i) ?: continue
            if (isAdvisorBanner(prompt)) continue
            out.put(prompt)
        }
        return out
    }
}
