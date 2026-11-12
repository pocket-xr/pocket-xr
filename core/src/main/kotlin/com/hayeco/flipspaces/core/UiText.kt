package com.hayeco.flipspaces.core

/**
 * Words from core that the user sees, in the phone's language. Core is plain Kotlin with no Android resources, so the
 * app sets [lookup] at start to read a key's text from its strings.xml (the key is the string's name there). With no
 * lookup (the unit tests), or a key the app does not have, the English given here is used, so the tests read English.
 */
object UiText {
    @Volatile var lookup: ((key: String) -> String?)? = null

    /** The text for [key], or [english] when there is no lookup or no such string. */
    fun get(key: String, english: String): String = lookup?.invoke(key) ?: english

    /** [get], then its %1$s / %1$d arguments filled in. */
    fun format(key: String, english: String, vararg args: Any?): String = String.format(get(key, english), *args)
}
