// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Reads the real strings.xml resources to test labels. */
class TestTexts : Texts {
    private val map = load()

    override fun t(key: String, vararg args: Any?): String {
        val raw = map[key] ?: error("missing text key: $key")
        return String.format(raw, *args)
    }

    companion object {
        fun load(): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).associate {
                val n = nodes.item(it)
                n.attributes.getNamedItem("name").nodeValue to
                    n.textContent.replace("\\'", "'").replace("\\\"", "\"")
            }
        }
    }
}
