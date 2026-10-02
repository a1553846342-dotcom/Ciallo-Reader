package com.example.download

import java.io.File
import java.util.zip.ZipFile

/**
 * 下载文件完整性校验（纯 JVM，可独立单测）。
 *
 * 先排除 HTML 错误页，再按“文件真实内容”而不是“任务声明的格式”判断：
 * 部分书源（尤其 zlib 旧版页面）会把非 epub 书标成 epub，这里识别出真实格式后直接放行，
 * 由调用方用真实格式改名、入库，避免“下载的是 mobi/txt 却按 epub 校验而失败”。
 */
object DownloadFileValidator {

    data class IntegrityResult(
        val valid: Boolean,
        /** 从文件内容识别出的真实格式（epub/mobi/txt/pdf/cbz…），识别不出为 null。 */
        val actualFormat: String?,
        /** true 表示下载内容其实是 HTML 错误页。 */
        val isHtmlErrorPage: Boolean,
        /** HTML 错误页的具体原因（如 zlib 每日下载额度用尽），无则 null。 */
        val htmlErrorHint: String? = null
    )

    fun validateFileIntegrity(file: File, format: String): IntegrityResult {
        if (!file.exists() || file.length() == 0L) {
            return IntegrityResult(false, null, false)
        }

        // 1. HTML 错误页伪装（404/503/验证页），任何格式都直接判失败
        //    注意：必须排除 ZIP（EPUB）文件——合法 EPUB 内部的小文件
        //    （mimetype/container.xml/nav.xhtml）可能未压缩且恰好落在文件头 4KB 内，
        //    nav.xhtml 的 <!DOCTYPE html>/<html> 会让“找 HTML 标记”误判成错误页。
        //    真实错误页绝不可能以 ZIP 魔数 PK 开头。
        val head = readHead(file)
        if (head != null) {
            val isZip = head.size >= 4 &&
                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
            if (!isZip) {
                val headStr = String(head, 0, head.size, Charsets.UTF_8).lowercase()
                val trimmed = headStr.trimStart()
                val html = trimmed.startsWith("<!doctype html") || trimmed.startsWith("<html") || trimmed.startsWith("<head>")
                val knownError = listOf("diamwall", "checking your browser", "verifying your browser", "daily limit", "page not found", "solve this captcha").any { it in headStr }
                if (html && (!format.equals("txt", true) || knownError)) {
                    return IntegrityResult(false, null, true, extractHtmlErrorHint(file))
                }
            }
        }

        val declared = format.lowercase().trim()
        val detected = detectRealFormat(file, head)

        // 2. 声明 epub 但内容其实是 mobi/txt/pdf/cbz…：按真实格式放行
        if (declared == "epub" && detected != null && detected != "epub") {
            return IntegrityResult(true, detected, false)
        }

        // 3. EPUB 结构校验（声明或内容为 epub 时）
        if (declared == "epub" || detected == "epub") {
            val ok = try {
                ZipFile(file).use { zip ->
                    zip.getEntry("META-INF/container.xml") != null ||
                        zip.getEntry("mimetype") != null ||
                        zip.entries().asSequence().any { entry ->
                            !entry.isDirectory && (
                                entry.name.endsWith(".opf", ignoreCase = true) ||
                                    entry.name.startsWith("META-INF/", ignoreCase = true)
                                )
                        }
                }
            } catch (e: Exception) {
                false
            }
            if (ok) {
                return IntegrityResult(true, "epub", false)
            }
            // 不是合法 EPUB：如果内容其实是文本（UTF-8/GBK/UTF-16 小说），按 txt 放行。
            // 这一分支不影响正常 EPUB（能通过上面 ZIP 结构校验的不会走到这里）。
            val textSample = readTextSample(file)
            if (isProbablyText(textSample)) {
                return IntegrityResult(true, "txt", false)
            }
            return IntegrityResult(false, detected, false)
        }

        // 4. 非 epub 声明：识别出真实格式就用真实格式，识别不出也保留原行为（不误杀）
        return IntegrityResult(true, detected, false)
    }

    /** 从 HTML 错误页里找具体原因，给用户看得懂的提示。 */
    private fun extractHtmlErrorHint(file: File): String? {
        val sample = try {
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                val n = stream.read(buffer)
                if (n <= 0) return null else String(buffer, 0, n, Charsets.UTF_8).lowercase()
            }
        } catch (e: Exception) {
            return null
        }
        return when {
            sample.contains("daily limit") || sample.contains("downloads_today") ||
                sample.contains("downloads_limit") || sample.contains("already reached") ->
                "今日下载次数已达上限（每日额度用尽，等待重置或提升额度）"
            sample.contains("page not found") || sample.contains("not found, try again") ->
                "页面不存在或下载链接已失效"
            sample.contains("checking your browser") || sample.contains("diamwall") ||
                sample.contains("verifying your browser") ->
                "站点返回了浏览器验证页（DiamWall 验证未通过）"
            sample.contains("downloading") && sample.contains("z-library") ->
                "站点下载页是 HTML 中转页（新站 /dl/ 不再直接返回文件，请重试或稍后再试）"
            else -> null
        }
    }

    private fun readHead(file: File): ByteArray? {
        return try {
            file.inputStream().use { stream ->
                val buffer = ByteArray(4096)
                val n = stream.read(buffer)
                if (n <= 0) null else buffer.copyOf(n)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 读取更大样本用于文本兜底判断（UTF-16 等头 4KB 不足以判定的场景）。 */
    private fun readTextSample(file: File): ByteArray {
        return try {
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                val n = stream.read(buffer)
                if (n <= 0) ByteArray(0) else buffer.copyOf(n)
            }
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    /** 按魔数/结构识别真实文件格式。 */
    private fun detectRealFormat(file: File, head: ByteArray?): String? {
        if (head == null || head.size < 4) return null

        // PDF：正常文件 %PDF 在第 0 字节；部分文件带 BOM/前导字节，
        // 允许在前 1KB 内出现 %PDF-，避免漏检后被误判成 TXT 导入导致乱码
        if (head.size >= 5) {
            val window = minOf(head.size, 1024)
            for (i in 0..window - 5) {
                if (head[i] == '%'.code.toByte() && head[i + 1] == 'P'.code.toByte() &&
                    head[i + 2] == 'D'.code.toByte() && head[i + 3] == 'F'.code.toByte() &&
                    head[i + 4] == '-'.code.toByte()
                ) {
                    return "pdf"
                }
            }
        }

        // FB2（FictionBook）：XML 根 <FictionBook>
        if (head.size >= 64) {
            val sample = String(head, 0, minOf(head.size, 4096), Charsets.UTF_8).lowercase()
            if (sample.contains("<?xml") && sample.contains("<fictionbook")) {
                return "fb2"
            }
        }

        // ZIP：EPUB（含 container.xml/mimetype）；否则只有含图片的才可能是漫画 CBZ
        if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            return try {
                ZipFile(file).use { zip ->
                    if (zip.getEntry("word/document.xml") != null) {
                        "docx"
                    } else if (zip.getEntry("META-INF/container.xml") != null ||
                        zip.getEntry("mimetype") != null ||
                        zip.entries().asSequence().any { entry ->
                            !entry.isDirectory && (
                                entry.name.endsWith(".opf", ignoreCase = true) ||
                                    entry.name.startsWith("META-INF/", ignoreCase = true)
                                )
                        }
                    ) {
                        "epub"
                    } else if (zip.entries().asSequence().any { entry ->
                            !entry.isDirectory &&
                                entry.name.substringAfterLast('.', "").lowercase() in setOf(
                                    "jpg", "jpeg", "png", "webp", "gif", "bmp"
                                )
                        }
                    ) {
                        "cbz"
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        // MOBI / AZW3：PDB 类型字段 "BOOKMOBI"（偏移 60）
        if (head.size >= 68 &&
            head[60] == 'B'.code.toByte() && head[61] == 'O'.code.toByte() &&
            head[62] == 'O'.code.toByte() && head[63] == 'K'.code.toByte() &&
            head[64] == 'M'.code.toByte() && head[65] == 'O'.code.toByte() &&
            head[66] == 'B'.code.toByte() && head[67] == 'I'.code.toByte()
        ) {
            return "mobi"
        }

        // 纯文本（UTF-8 / GBK / ASCII 小说）
        if (isProbablyText(head)) {
            return "txt"
        }
        return null
    }

    private fun isProbablyText(head: ByteArray): Boolean {
        if (head.isEmpty()) return false

        // UTF-16 带 BOM
        if (head.size >= 2 && ((head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) ||
                (head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()))
        ) {
            return true
        }
        // UTF-16 无 BOM：每两个字节中一个是 0，另一个是可打印字符
        if (head.size >= 4) {
            val isLe = head[0] != 0.toByte() && head[1] == 0.toByte()
            val isBe = head[0] == 0.toByte() && head[1] != 0.toByte()
            if (isLe || isBe) {
                var textUnits = 0
                var bad = 0
                val units = minOf(head.size / 2, 2048)
                for (i in 0 until units) {
                    val lo = head[i * 2].toInt() and 0xFF
                    val hi = head[i * 2 + 1].toInt() and 0xFF
                    val c = if (isLe) lo else hi
                    val z = if (isLe) hi else lo
                    when {
                        z == 0 && (c in 0x09..0x0D || c in 0x20..0x7E || c >= 0x80) -> textUnits++
                        z == 0 -> bad++
                        else -> bad += 2
                    }
                }
                if (textUnits > 0 && bad < textUnits) return true
            }
        }

        var nul = 0
        var ctrl = 0
        var textLike = 0
        val n = head.size
        for (i in 0 until n) {
            val b = head[i].toInt() and 0xFF
            when {
                b == 0 -> nul++
                b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D -> ctrl++
                    b in 0x20..0x7E || b >= 0x80 || b == 0x09 || b == 0x0A || b == 0x0D -> textLike++
                }
            }
            // 放宽阈值：UTF-16 无 BOM、部分 GBK/带少量控制字符的 TXT 也能被识别为文本
            return nul * 100 / n < 35 &&
                ctrl * 100 / n < 5 &&
                textLike * 100 / n >= 60
        }
}
