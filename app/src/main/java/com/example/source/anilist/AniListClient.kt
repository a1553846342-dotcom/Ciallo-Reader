package com.example.source.anilist

import kotlinx.coroutines.Dispatchers
import com.example.source.executeCancellable
import com.example.data.readImportBytes
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AniList GraphQL 标题数据客户端（第七轮第 7 条）。
 *
 * AniList 在本项目中只扮演"标题数据提供者"——按 media id 游标增量拉取
 * MANGA 类型作品的多语言标题（romaji / english / native / synonyms），
 * 不作为书源、不实时参与搜索（搜索只查本地库）。
 *
 * 网络层约定（规范第 9/12 点）：
 * - 任何网络异常 / 限流 / 超时 / 响应畸形都以 null / 空结果返回，
 *   绝不让异常外溢影响书库搜索链路；
 * - id_greater 游标分页：断点续传安全、重放幂等（配合 DAO IGNORE 策略）。
 */
class AniListClient(
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        const val ENDPOINT = "https://graphql.anilist.co"
        const val PER_PAGE = 50

        fun defaultClient(): OkHttpClient = com.example.source.SharedHttpTransport.builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val QUERY = """
            query (${'$'}cursor: Int, ${'$'}perPage: Int) {
              Page(page: 1, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage }
                media(type: MANGA, id_greater: ${'$'}cursor, sort: ID, isAdult: false) {
                  id
                  title { romaji english native }
                  synonyms
                }
              }
            }
        """.trimIndent()

        /** 第九轮：热门优先分页查询（POPULARITY_DESC + 页码游标）——
         *  首个同步会话即覆盖最热门作品，本地标题库从第一天起对常见搜索可用。 */
        private val POPULAR_QUERY = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage }
                media(type: MANGA, sort: POPULARITY_DESC, isAdult: false) {
                  id
                  title { romaji english native }
                  synonyms
                }
              }
            }
        """.trimIndent()
    }

    /**
     * 拉取 id 大于 [cursor] 的一页标题数据。
     * @return null = 网络/解析失败（调用方安全跳过）；空 media = 已同步到末尾
     */
    suspend fun fetchPageAfter(cursor: Int): AniListSyncPage? = fetch(QUERY, "cursor", cursor)

    /** 第九轮：按热度排名拉取第 [page] 页（页码从 1 起）。游标语义 = 页码。 */
    suspend fun fetchPopularPage(page: Int): AniListSyncPage? = fetch(POPULAR_QUERY, "page", page)

    private suspend fun fetch(query: String, variableName: String, value: Int): AniListSyncPage? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("query", query)
                    .put(
                        "variables",
                        JSONObject().put(variableName, value).put("perPage", PER_PAGE)
                    ).toString()
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .post(body.toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).executeCancellable().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val text = response.body?.byteStream()?.use { it.readImportBytes(2*1024*1024).toString(Charsets.UTF_8) } ?: return@runCatching null
                    val page = JSONObject(text)
                        .optJSONObject("data")
                        ?.optJSONObject("Page")
                        ?: return@runCatching null
                    val hasMore = page.optJSONObject("pageInfo")?.optBoolean("hasNextPage") ?: false
                    val mediaArray = page.optJSONArray("media") ?: return@runCatching AniListSyncPage(emptyList(), false)
                    val media = ArrayList<AniListMediaTitles>(mediaArray.length())
                    for (i in 0 until mediaArray.length()) {
                        val m = mediaArray.optJSONObject(i) ?: continue
                        val id = m.optInt("id", -1)
                        if (id <= 0) continue
                        val title = m.optJSONObject("title")
                        val synonymsArray = m.optJSONArray("synonyms")
                        val synonyms = ArrayList<String>(synonymsArray?.length() ?: 0)
                        if (synonymsArray != null) {
                            for (k in 0 until synonymsArray.length()) {
                                synonymsArray.optString(k, "")?.takeIf { it.isNotBlank() }?.let { synonyms.add(it) }
                            }
                        }
                        media.add(
                            AniListMediaTitles(
                                mediaId = id,
                                romaji = title?.optString("romaji", null)?.takeUnless { it.isNullOrEmpty() || it == "null" },
                                english = title?.optString("english", null)?.takeUnless { it.isNullOrEmpty() || it == "null" },
                                native = title?.optString("native", null)?.takeUnless { it.isNullOrEmpty() || it == "null" },
                                synonyms = synonyms,
                            )
                        )
                    }
                    AniListSyncPage(media = media, hasMore = hasMore)
                }
            }.getOrElse { kotlinx.coroutines.currentCoroutineContext().ensureActive(); null }
        }
}
