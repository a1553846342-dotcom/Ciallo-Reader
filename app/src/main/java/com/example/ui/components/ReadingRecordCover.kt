package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.library.GenericCoverLoader
import com.example.library.ZLibraryCoverLoader

/** Statistics share the library's image cache, cookies and source-specific Referer. */
@Composable
internal fun ReadingRecordCover(
    cover: Any, sourceId: String?, headers: Map<String, String>,
    contentDescription: String?, modifier: Modifier
) {
    val context = LocalContext.current
    val request = remember(context, cover, headers) {
        ImageRequest.Builder(context).data(cover).apply {
            headers.forEach { (name, value) -> addHeader(name, value) }
        }.build()
    }
    AsyncImage(model = request,
        imageLoader = if (sourceId == "zlibrary") ZLibraryCoverLoader.get(context) else GenericCoverLoader.get(context),
        contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = modifier)
}
