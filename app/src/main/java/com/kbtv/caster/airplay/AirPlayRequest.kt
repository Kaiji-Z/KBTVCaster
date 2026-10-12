package com.kbtv.caster.airplay

import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A parsed AirPlay receiver request — RTSP/HTTP style, one per message:
 *
 *   POST /play?... HTTP/1.1\r\nHeader: value\r\n...\r\n\r\n<body>
 *
 * Pure Kotlin with no android.* dependency so the parsing layer stays
 * JVM-unit-testable (VERIFICATION.md §3.1 Layer 1). Malformed input parses
 * to null — never throws (reverse criterion).
 */
internal data class AirPlayRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>, // keys lowercased for case-insensitive lookup
    val body: String,
) {
    companion object {
        fun parse(raw: String): AirPlayRequest? {
            if (raw.isEmpty()) return null
            val parts = raw.split("\r\n\r\n", limit = 2)
            val lines = parts[0].split("\r\n")
            if (lines.isEmpty()) return null
            val requestLine = lines[0].split(" ")
            if (requestLine.size < 2) return null

            val method = requestLine[0].uppercase()
            val pathQuery = requestLine[1].split("?", limit = 2)
            val path = pathQuery[0]
            val query = if (pathQuery.size == 2) parseQuery(pathQuery[1]) else emptyMap()

            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(':')
                if (idx <= 0) continue
                headers[lines[i].substring(0, idx).trim().lowercase()] =
                    lines[i].substring(idx + 1).trim()
            }
            return AirPlayRequest(
                method, path, query, headers,
                if (parts.size == 2) parts[1] else ""
            )
        }

        private fun parseQuery(q: String): Map<String, String> =
            q.split('&').mapNotNull { kv ->
                val idx = kv.indexOf('=')
                if (idx <= 0) null
                else urlDecode(kv.substring(0, idx)) to urlDecode(kv.substring(idx + 1))
            }.toMap()

        // Charset overload needs API 33 (lint NewApi); the String-charset form
        // covers minSdk 23 and only throws on a bad charset name, which is static.
        private fun urlDecode(s: String): String =
            try {
                URLDecoder.decode(s, "UTF-8")
            } catch (e: java.io.UnsupportedEncodingException) {
                s
            }
    }
}

/**
 * Minimal Apple text-plist reader: flat <dict> of key → string/integer/real/
 * true/false, which is all AirPlay /play bodies carry (Content-Location,
 * Start-Position). Binary plists are deliberately NOT handled here —
 * mirroring senders use them and go through the native layer instead.
 * Any parse failure returns an empty map.
 */
internal fun parseTextPlist(xml: String): Map<String, String> {
    return try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
            // Feature unsupported on this parser — proceed without it.
        }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val dicts = doc.getElementsByTagName("dict")
        if (dicts.length == 0) {
            emptyMap()
        } else {
            val out = LinkedHashMap<String, String>()
            var node = dicts.item(0).firstChild
            var pendingKey: String? = null
            while (node != null) {
                if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                    val name = node.nodeName
                    val text = node.textContent?.trim() ?: ""
                    if (name == "key") {
                        pendingKey = text
                    } else {
                        pendingKey?.let { key ->
                            out[key] = when (name) {
                                "true" -> "true"
                                "false" -> "false"
                                else -> text
                            }
                        }
                        pendingKey = null
                    }
                }
                node = node.nextSibling
            }
            out
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
