package com.example.source

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only web links may be passed from a source script to the system browser. */
internal fun validRegistrationUrl(value: String?): String? = value?.trim()
    ?.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()
