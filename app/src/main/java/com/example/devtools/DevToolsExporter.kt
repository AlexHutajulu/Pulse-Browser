package com.example.devtools

import org.json.JSONArray
import org.json.JSONObject

object DevToolsExporter {

    fun exportNetworkEntriesToJson(entries: List<NetworkEntry>): String {
        val root = JSONArray()
        for (entry in entries) {
            val obj = JSONObject()
            obj.put("id", entry.id)
            obj.put("timestamp", entry.timestamp)
            obj.put("url", entry.url)
            obj.put("method", entry.method)
            obj.put("host", entry.host)
            obj.put("scheme", entry.scheme)
            obj.put("isMainFrame", entry.isMainFrame)
            obj.put("hasGesture", entry.hasGesture)
            obj.put("resourceType", entry.resourceType.name)
            obj.put("status", entry.status.name)
            obj.put("statusCode", entry.statusCode ?: JSONObject.NULL)
            obj.put("mimeType", entry.mimeType ?: JSONObject.NULL)
            obj.put("blockedReason", entry.blockedReason ?: JSONObject.NULL)
            obj.put("durationMs", entry.durationMs ?: JSONObject.NULL)
            root.put(obj)
        }
        return root.toString(2)
    }

    fun exportConsoleEntriesToJson(entries: List<ConsoleEntry>): String {
        val root = JSONArray()
        for (entry in entries) {
            val obj = JSONObject()
            obj.put("id", entry.id)
            obj.put("timestamp", entry.timestamp)
            obj.put("level", entry.level.name)
            obj.put("message", entry.message)
            obj.put("sourceId", entry.sourceId ?: JSONObject.NULL)
            obj.put("lineNumber", entry.lineNumber ?: JSONObject.NULL)
            root.put(obj)
        }
        return root.toString(2)
    }
}
