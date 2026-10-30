// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

/** Text provider by key (string resource name): implemented by the Activity, or by a fake in tests. */
fun interface Texts {
    fun t(key: String, vararg args: Any?): String
}
