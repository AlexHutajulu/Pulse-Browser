package com.example.devtools

import android.webkit.JavascriptInterface
import android.util.Log
import org.json.JSONObject

class DevToolsJsBridge(
    private val onElementSelected: (SelectedElement) -> Unit,
    private val onConsoleBridgeMessage: (ConsoleEntry) -> Unit
) {
    private val TAG = "DevToolsJsBridge"
    private val MAX_BRIDGE_PAYLOAD_LENGTH = 16_384
    private val ELEMENT_SELECT_COOLDOWN_MS = 80L
    private val MAX_CONSOLE_MESSAGES_PER_WINDOW = 60
    private val CONSOLE_WINDOW_MS = 1000L

    @Volatile
    private var lastElementSelectTime = 0L

    @Volatile
    private var consoleWindowStart = 0L

    @Volatile
    private var consoleCountInWindow = 0

    @JavascriptInterface
    fun onElementSelected(json: String) {
        if (json.length > MAX_BRIDGE_PAYLOAD_LENGTH) return
        val now = System.currentTimeMillis()
        if (now - lastElementSelectTime < ELEMENT_SELECT_COOLDOWN_MS) return
        lastElementSelectTime = now
        try {
            val root = JSONObject(json)
            val rectObj = root.optJSONObject("rect") ?: JSONObject()
            val rect = ElementRect(
                x = rectObj.optDouble("x", 0.0).toFloat(),
                y = rectObj.optDouble("y", 0.0).toFloat(),
                width = rectObj.optDouble("width", 0.0).toFloat(),
                height = rectObj.optDouble("height", 0.0).toFloat()
            )

            val classesList = mutableListOf<String>()
            val classesArray = root.optJSONArray("classes")
            if (classesArray != null) {
                for (i in 0 until classesArray.length().coerceAtMost(50)) {
                    classesList.add(classesArray.optString(i).take(120))
                }
            }

            val attributesMap = mutableMapOf<String, String>()
            val attrsObj = root.optJSONObject("attributes")
            if (attrsObj != null) {
                val keys = attrsObj.keys()
                var count = 0
                while (keys.hasNext() && count < 60) {
                    val key = keys.next()
                    attributesMap[key.take(80)] = attrsObj.optString(key).take(500)
                    count++
                }
            }

            val stylesMap = mutableMapOf<String, String>()
            val stylesObj = root.optJSONObject("computedStyles")
            if (stylesObj != null) {
                val keys = stylesObj.keys()
                var count = 0
                while (keys.hasNext() && count < 80) {
                    val key = keys.next()
                    stylesMap[key.take(80)] = stylesObj.optString(key).take(250)
                    count++
                }
            }

            val elementId = if (root.isNull("id")) null else root.optString("id").takeIf { it.isNotEmpty() && it != "null" }?.take(120)
            val element = SelectedElement(
                tagName = root.optString("tagName", "unknown").lowercase().take(40),
                id = elementId,
                classes = classesList,
                textPreview = root.optString("textPreview", "").take(500),
                attributes = attributesMap,
                computedStyles = stylesMap,
                selector = root.optString("selector", "").take(250),
                rect = rect
            )

            onElementSelected(element)

        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing selected element: ${e.message}")
        }
    }

    @JavascriptInterface
    fun onConsoleMessage(json: String) {
        if (json.length > MAX_BRIDGE_PAYLOAD_LENGTH) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - consoleWindowStart > CONSOLE_WINDOW_MS) {
                consoleWindowStart = now
                consoleCountInWindow = 0
            }
            if (++consoleCountInWindow > MAX_CONSOLE_MESSAGES_MS_LIMIT) return
        }
        try {
            val root = JSONObject(json)
            val levelStr = root.optString("level", "LOG")
            val level = runCatching { ConsoleLevel.valueOf(levelStr) }.getOrDefault(ConsoleLevel.LOG)
            
            val sourceIdVal = if (root.isNull("sourceId")) null else root.optString("sourceId").takeIf { it.isNotEmpty() && it != "null" }?.take(256)
            val entry = ConsoleEntry(
                id = java.util.UUID.randomUUID().toString(),
                timestamp = now,
                level = level,
                message = root.optString("message", "").take(2000),
                sourceId = sourceIdVal,
                lineNumber = if (root.has("lineNumber") && !root.isNull("lineNumber")) root.optInt("lineNumber") else null
            )

            onConsoleBridgeMessage(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing console message: ${e.message}")
        }
    }

    companion object {
        private const val MAX_CONSOLE_MESSAGES_MS_LIMIT = 60
    }
}
