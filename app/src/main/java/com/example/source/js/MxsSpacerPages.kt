package com.example.source.js

import android.graphics.Bitmap

/** MXS sometimes splits a white margin into a separate short JPEG at chapter start. */
internal fun isMxsWhiteSpacer(bitmap: Bitmap): Boolean {
    if (bitmap.width < 300 || bitmap.height > 512 || bitmap.height > bitmap.width / 2) return false
    val row = IntArray(bitmap.width)
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        if (row.any { pixel ->
            (pixel ushr 24) != 0 && ((pixel shr 16 and 255) < 254 ||
                (pixel shr 8 and 255) < 254 || (pixel and 255) < 254)
        }) return false
    }
    return true
}

internal suspend fun stripLeadingMxsSpacers(
    urls: List<String>,
    load: suspend (String) -> Bitmap?
): List<String> {
    var skipped = 0
    // A bounded prefix check keeps opening a chapter predictable. The first real
    // page is warmed in the reader's own cache by the caller.
    for (url in urls.take(3)) {
        val bitmap = load(url) ?: break
        if (!isMxsWhiteSpacer(bitmap)) break
        skipped++
    }
    return urls.drop(skipped)
}
