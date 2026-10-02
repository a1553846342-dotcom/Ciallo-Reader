package com.example.data

import android.content.Context
import android.database.Cursor
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable, streamed user data backup. Auth stores, models and active downloads are excluded. */
internal object BackupArchive {
    private val tables = listOf("books", "chapters", "bookmarks", "highlights", "categories",
        "reading_records", "reading_sessions", "favorites", "comic_progress", "comic_chapter_read",
        "favorite_categories", "god_moments")
    private val ownedNames = setOf("imports", "epub_images", "docx_images", "fb2_images", "mobi_images",
        "epub_covers", "fb2_covers", "mobi_covers", "comic_covers", "god_covers", "sample_comic", "chapter_catalogs")
    private fun owned(name: String) = name in ownedNames || name.startsWith("comics_") || name.startsWith("restored_") || name.startsWith("custom_poster_") || name.startsWith("custom_background_") || name.startsWith("custom_app_bg_") || name.startsWith("custom_font_")
    private fun safeSetting(key: String): Boolean = !Regex("(?i)(cookie|token|password|credential|secret|api.?key|pin_|salt|auth)").containsMatchIn(key)
    private val settingTypes = buildMap<String, String> {
        listOf("reading_totals_reconciled_v1","first_line_indent","splash_pure_mode","auto_night_mode","blue_light_filter","preload_wifi_only","keep_screen_on","haptics_enabled","incognito_browsing_enabled","favorites_protected","multi_language_search","show_overlay_header_footer","card_tweaks_migrated_v2","has_seen_onboarding","has_seen_welcome","has_configured_source","has_imported_local_book","has_imported_community_comics_v1","show_adult_sources","js_source_health_checked_v3").forEach { put(it,"boolean") }
        listOf("font_size","line_height","blue_light_alpha","tts_speed","tts_pitch","reader_brightness","card_blur_dp","card_corner_dp","card_tilt_deg","card_cam_mult","card_ripple_a","card_tint_mix","card_press_s","card_press_r","card_alpha").forEach { put(it,"float") }
        listOf("margin_horizontal","reader_theme","custom_bg_color","custom_text_color","font_family_index","page_turn_mode","app_background_mode","app_background_dim","screen_orientation_lock","rest_reminder_minutes","shelf_drag_hint_shown","click_zone_left_action","click_zone_center_action","click_zone_right_action","color_primary_index","color_secondary_index","render_quality","daily_goal_minutes").forEach { put(it,"int") }
        listOf("total_read_time_seconds","legacy_unattributed_read_seconds").forEach { put(it,"long") }
        listOf("custom_splash_poster_uri","custom_app_background_uri","custom_font_path","js_source_repo_url","search_history_v1").forEach { put(it,"string") }
    }
    private fun settingType(key: String) = settingTypes[key] ?: "long".takeIf { Regex("daily_read_time_\\d{4}-\\d{2}-\\d{2}").matches(key) }
    private fun encode(value:Any?):JSONObject {
        val type=when(value) { is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is Set<*> -> "set"; else -> "string" }
        return JSONObject().put("type",type).put("value",if(value is Set<*>) org.json.JSONArray(value.toList()) else value)
    }
    suspend fun export(context: Context, target: File): File = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val stage = File(context.cacheDir, "backup_${UUID.randomUUID()}.rows")
        val temp = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            target.parentFile?.mkdirs()
            val root = context.filesDir.canonicalPath
            db.withTransaction {
                stage.bufferedWriter().use { out ->
                    out.appendLine(JSONObject().put("version", 1).put("schema", 12).toString())
                    for (table in tables) {
                        db.openHelper.writableDatabase.query("SELECT * FROM `$table`").use { c ->
                            while (c.moveToNext()) {
                                currentCoroutineContext().ensureActive()
                                val row = JSONObject()
                                for (i in 0 until c.columnCount) row.put(c.getColumnName(i), when(c.getType(i)) {
                                    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                    Cursor.FIELD_TYPE_STRING -> c.getString(i).replace(root, "@FILES@")
                                    else -> error("Unsupported backup column")
                                })
                                out.appendLine(JSONObject().put("table", table).put("row", row).toString())
                            }
                        }
                    }
                }
            }
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                var expanded = 0L
                suspend fun entry(name: String, file: File) {
                    require(file.length() <= 512L*1024*1024 && expanded + file.length() <= 4L*1024*1024*1024) { "备份内容超过容量限制" }
                    expanded += file.length()
                    zip.putNextEntry(ZipEntry(name)); file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; zip.write(buffer, 0, n) }
                    }; zip.closeEntry()
                }
                entry("database.rows", stage)
                val settings = JSONObject()
                context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE).all
                    .filterKeys(::safeSetting).forEach { (key, value) -> settings.put(key, encode(if (value is String) value.replace(root, "@FILES@") else value)) }
                zip.putNextEntry(ZipEntry("settings.json")); zip.write(settings.toString().toByteArray()); zip.closeEntry()
                context.filesDir.listFiles().orEmpty().filter { owned(it.name) }.forEach { dir ->
                    dir.walkTopDown().filter { it.isFile }.forEach { file ->
                        currentCoroutineContext().ensureActive()
                        require(file.canonicalPath.startsWith(context.filesDir.canonicalPath+File.separator))
                        if (!file.name.endsWith(".part") && !file.name.endsWith(".tmp")) entry("files/" + file.relativeTo(context.filesDir).invariantSeparatorsPath, file)
                    }
                }
            }
            check(temp.renameTo(target)) { "备份保存失败" }
            target
        } finally { stage.delete(); temp.delete() }
    }
    suspend fun restore(context: Context, archive: File): Boolean = withContext(Dispatchers.IO) {
        val stage = File(context.filesDir, "restored_${UUID.randomUUID()}").apply { mkdirs() }
        var committed = false
        try {
            val budget = ArchiveBudget(512L * 1024 * 1024, 4L * 1024 * 1024 * 1024, 50_000)
            val seen = HashSet<String>()
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    require(entry.name.length<=1024 && seen.size<50_000)
                    require(seen.add(entry.name)) { "备份包含重复条目" }
                    require(entry.name == "database.rows" || entry.name == "settings.json" || entry.name.startsWith("files/"))
                    val destination = ArchiveBudget.destination(stage, entry.name)
                    if (!entry.isDirectory) {
                        destination.parentFile?.mkdirs()
                        destination.outputStream().use { budget.copyEntry(zip, it) }
                    }
                    zip.closeEntry()
                }
            }
            val settingsFile = File(stage, "settings.json")
            require(settingsFile.isFile && settingsFile.length() <= 1024 * 1024)
            val settings = JSONObject(settingsFile.readText())
            val rows = File(stage, "database.rows")
            val root = File(stage, "files").absolutePath
            val db = AppDatabase.getDatabase(context)
            val columnCache=tables.associateWith { table -> db.openHelper.writableDatabase.query("PRAGMA table_info(`$table`)").use { c ->
                buildMap { while(c.moveToNext()) put(c.getString(1),c.getString(2) to (c.getInt(3)!=0)) }
            } }
            val editor = context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE).edit()
            context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE).all.keys
                .filter { safeSetting(it) && settingType(it)!=null }.forEach { editor.remove(it) }
            settings.keys().forEach { key ->
                if(safeSetting(key)) {
                    val expected=settingType(key) ?: return@forEach
                    val item=settings.getJSONObject(key)
                    require(item.getString("type")==expected) { "备份设置类型不符: $key" }
                    when(expected) {
                        "boolean" -> editor.putBoolean(key,item.getBoolean("value"))
                        "int" -> {
                            val value=item.getInt("value")
                            val range=when(key) { "reader_theme" -> 0..5; "page_turn_mode" -> 0..4; "screen_orientation_lock" -> 0..2; "font_family_index" -> 0..20; "app_background_mode" -> 0..1; "app_background_dim" -> 0..50; "daily_goal_minutes","rest_reminder_minutes" -> 0..1440; else -> Int.MIN_VALUE..Int.MAX_VALUE }
                            require(value in range) { "备份设置越界: $key" }; editor.putInt(key,value)
                        }
                        "long" -> editor.putLong(key,item.getLong("value"))
                        "float" -> {
                            val number=item.getDouble("value").toFloat()
                            require(number.isFinite() && kotlin.math.abs(number)<=10_000)
                            editor.putFloat(key,when(key) { "font_size" -> number.coerceIn(8f,80f); "line_height" -> number.coerceIn(8f,120f); else -> number })
                        }
                        "string" -> editor.putString(key,item.getString("value").replace("@FILES@",root))
                        "set" -> { val a=item.getJSONArray("value"); require(a.length()<=10000); editor.putStringSet(key,(0 until a.length()).map { a.getString(it) }.toSet()) }
                    }
                }
            }
            db.withTransaction {
                tables.asReversed().forEach { db.openHelper.writableDatabase.execSQL("DELETE FROM `$it`") }
                rows.bufferedReader().use { reader ->
                    val header = JSONObject(readBoundedLine(reader) ?: error("缺少备份元数据"))
                    require(header.getInt("version") == 1 && header.getInt("schema") == 12) { "不支持的备份版本" }
                    var rowCount = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = readBoundedLine(reader) ?: break
                        require(++rowCount <= 1_000_000)
                        val item = JSONObject(line)
                        val table = item.getString("table")
                        require(table in tables)
                        val row = item.getJSONObject("row")
                        if(table=="chapters") require(row.getString("content").length<=256_000) { "备份章节超过存储安全限制" }
                        if(table in setOf("reading_records","reading_sessions")) require(row.getLong("durationSeconds")>=0)
                        val columns = columnCache.getValue(table)
                        val names = row.keys().asSequence().toList()
                        require(names.toSet() == columns.keys) { "备份列与数据库不一致" }
                        val values = names.map { name ->
                            val value=row.get(name)
                            val (type,required)=columns.getValue(name)
                            require(value!=JSONObject.NULL || !required) { "备份必填字段为空" }
                            require(value==JSONObject.NULL || when(type) { "TEXT" -> value is String; "INTEGER" -> value is Int || value is Long; "REAL" -> value is Number && value.toDouble().isFinite(); else -> false }) { "备份数据库字段类型错误" }
                            when (value) {
                                JSONObject.NULL -> null
                                is String -> value.replace("@FILES@", root)
                                is Number -> value
                                else -> error("无效备份字段")
                            }
                        }.toTypedArray()
                        db.openHelper.writableDatabase.execSQL("INSERT INTO `$table` (${names.joinToString { "`$it`" }}) VALUES (${names.joinToString { "?" }})", values)
                    }
                }
                for(table in listOf("chapters","bookmarks","highlights")) {
                    val orphan=db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM `$table` x LEFT JOIN books b ON x.bookId=b.id WHERE b.id IS NULL").use { it.moveToFirst(); it.getLong(0) }
                    require(orphan==0L) { "备份包含孤立的书籍关联" }
                }
            }
            committed = true
            check(editor.commit()) { "设置恢复失败，书籍数据已恢复" }
            rows.delete(); settingsFile.delete()
            true
        } finally { if (!committed) stage.deleteRecursively() }
    }
    private fun readBoundedLine(reader: java.io.Reader): String? {
        val line = StringBuilder()
        while(true) {
            val ch = reader.read()
            if (ch < 0) return line.toString().takeIf { it.isNotEmpty() }
            if (ch == 10) return line.toString()
            require(line.length < 512 * 1024) { "备份行过大" }
            line.append(ch.toChar())
        }
    }
}
