package com.example.source.parser

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Legado（开源阅读 3.0）书源规则解释器（HTML 子集）。
 *
 * 支持社区书源中最常用的语法：
 * - 默认规则段：class.xxx.0 / id.xxx.0 / tag.a.0 / text.关键词 / children
 *   - 位置：0 起正数、-1 倒数、!0:1 排除、留空取全部
 *   - 列表规则首字符 `-` 表示倒序
 * - CSS 规则：@css:.name@text（也兼容旧写法 .name@text / img@src）
 * - 连接符：|| 取第一个有值的规则；&& 合并所有取值
 * - 内容关键字：text / ownText / textNodes / html / all / href / src / 任意属性
 * - 正则替换：规则##正则##替换（替换为空时第二个 ## 可省略）
 *
 * 不支持（会返回空并提示）：@js: 脚本、webView、嗅探 sourceRegex。
 */
object LegadoRule {

    private val segmentTypes = setOf("class", "id", "tag", "text", "children", "all")
    private val contentKeywords = setOf("text", "textNodes", "ownText", "html", "all")
    private val commonTags = setOf(
        "a", "li", "img", "div", "p", "span", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6",
        "dl", "dd", "dt", "table", "tr", "td", "th", "section", "article", "nav", "button",
        "em", "b", "i", "strong", "figure", "figcaption", "mip-img", "mip-link", "amp-img"
    )

    /** 判断规则是否面向 JSON（JSONPath），而非 HTML 元素。 */
    fun isJsonRule(rawRule: String): Boolean {
        val r = rawRule.trim()
        return r.startsWith("$") || r.startsWith("@json:") || r.startsWith("@js:")
    }

    /** 去除 @json: / $ 前缀，得到可交给 JsonPathResolver 的路径。 */
    fun cleanJsonPath(rawRule: String): String {
        var r = rawRule.trim()
        if (r.startsWith("@json:")) r = r.removePrefix("@json:").trim()
        if (r.startsWith("$")) r = r.removePrefix("$").trimStart('.')
        return r
    }

    /** 按列表规则选取元素。支持 - 倒序、|| 回退、&& 合并。 */
    fun selectElements(root: Element, rawRule: String): List<Element> {
        RuleBudget.validate(rawRule)
        var rule = rawRule.trim()
        if (rule.isBlank()) return emptyList()
        var reverse = false
        if (rule.startsWith("-") && rule.length > 1) {
            reverse = true
            rule = rule.substring(1).trim()
        }
        val branches = rule.split("||").map { it.trim() }.filter { it.isNotBlank() }
        for (branch in branches) {
            val elements = selectBranchElements(root, branch)
            if (elements.isNotEmpty()) {
                return if (reverse) elements.reversed() else elements
            }
        }
        return emptyList()
    }

    /** 求值规则，返回全部匹配值（用于 && 合并 / 列表值）。 */
    fun evalValues(root: Element, rawRule: String): List<String> {
        RuleBudget.validate(rawRule)
        var rule = rawRule.trim()
        if (rule.isBlank()) return emptyList()
        if (isJsonRule(rule)) return emptyList()
        if (rule.startsWith("@js:") || rule.contains("{{") || rule.contains("{$")) return emptyList()

        val regexInfo = splitRegexReplacement(rule)
        val baseRule = regexInfo?.first ?: rule

        val orBranches = baseRule.split("||").map { it.trim() }.filter { it.isNotBlank() }
        for (branch in orBranches) {
            val values = evalBranchValues(root, branch)
            if (values.isNotEmpty()) {
                return values.map { applyRegexReplacement(it, regexInfo?.second) }
                    .filter { it.isNotBlank() }
            }
        }
        return emptyList()
    }

    /** 取第一个非空值，多数字段规则使用。 */
    fun evalFirst(root: Element, rawRule: String): String? = evalValues(root, rawRule).firstOrNull()

    private fun evalBranchValues(root: Element, rule: String): List<String> {
        val andParts = rule.split("&&").map { it.trim() }.filter { it.isNotBlank() }
        if (andParts.size <= 1) return evalSingleValue(root, rule)
        val merged = mutableListOf<String>()
        for (part in andParts) {
            merged.addAll(evalSingleValue(root, part))
        }
        return merged
    }

    private fun evalSingleValue(root: Element, rule: String): List<String> {
        val r = rule.trim()
        if (r.isBlank()) return emptyList()
        if (r.startsWith("@css:")) {
            return evalCssValue(root, r.removePrefix("@css:").trim())
        }
        // 内容直取：@text / @href / @src 表示对当前元素取内容
        if (r.startsWith("@") && !r.startsWith("@css:")) {
            return listOfNotNull(extractContent(root, r.substring(1)))
        }

        val firstSegment = r.substringBefore('@').trim()
        if (looksLikeLegadoSegments(firstSegment)) {
            return evalLegadoSegments(root, r)
        }

        // 旧格式兼容：css@attr
        val atIndex = r.indexOf('@')
        val css = if (atIndex >= 0) r.substring(0, atIndex).trim() else r.trim()
        val attr = if (atIndex >= 0) r.substring(atIndex + 1).trim() else "text"
        var el = try { if (css.isBlank()) root else root.selectFirst(css) } catch (e: Exception) { null }
        // Legado 索引写法混在 CSS 里（如 ".newrap a.0"）：把 a.0 的 .N 当索引去掉重试
        if (el == null && css.contains(Regex("""\.\d+"""))) {
            val normalized = css.replace(Regex("""\.(\d+)(?=[\s.#:\[]|$)"""), "").trim()
            if (normalized.isNotBlank() && normalized != css) {
                el = try { root.selectFirst(normalized) } catch (e: Exception) { null }
            }
        }
        if (el == null) return emptyList()
        return listOfNotNull(extractContent(el, attr))
    }

    private fun evalCssValue(root: Element, cssRule: String): List<String> {
        val atIndex = cssRule.indexOf('@')
        val css = (if (atIndex >= 0) cssRule.substring(0, atIndex) else cssRule).trim()
        val attr = if (atIndex >= 0) cssRule.substring(atIndex + 1).trim() else "text"
        if (css.isBlank()) return emptyList()
        return try {
            root.select(css).mapNotNull { extractContent(it, attr) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun evalLegadoSegments(root: Element, rule: String): List<String> {
        val segments = rule.split("@").map { it.trim() }.filter { it.isNotBlank() }
        if (segments.isEmpty()) return emptyList()

        var current: List<Element> = listOf(root)
        var content = "text"

        for ((index, seg) in segments.withIndex()) {
            val isLast = index == segments.lastIndex
            if (isLast && isContentSegment(seg)) {
                content = seg
                break
            }
            current = applySegment(current, seg)
            if (current.isEmpty()) return emptyList()
            if (isLast) content = "text"
        }
        return current.mapNotNull { extractContent(it, content) }
    }

    private fun isContentSegment(seg: String): Boolean {
        val s = seg.trim()
        if (s.startsWith(".") || s.startsWith("#")) return false
        val first = s.substringBefore('.').lowercase()
        return first in contentKeywords || (first !in segmentTypes && first !in commonTags)
    }

    private fun applySegment(current: List<Element>, seg: String): List<Element> {
        var s = seg.trim()
        // .row.2 / .row!0 / .itemBox 等 CSS 类名写法统一按 class 段处理
        if (s.startsWith(".") && !s.contains(" ")) {
            s = "class" + s
        }
        val parts = s.split(".").filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()
        val type = parts[0].lowercase()
        var name = parts.getOrNull(1).orEmpty()
        var position = parts.getOrNull(2).orEmpty()
        // Legado 简写：a.0 / img / button.0 -> tag.a.0 / tag.img / tag.button.0
        if (type !in segmentTypes && type in commonTags) {
            val tagName = parts[0]
            val tagPosition = parts.getOrNull(1).orEmpty()
            return applyPosition(
                current.flatMap { it.getElementsByTag(tagName).toList() },
                tagPosition
            )
        }
        // class.a b 多类名写法：class.view-main-1 readForm -> CSS .view-main-1.readForm
        if (type == "class" && name.contains(" ")) {
            val classes = name.split(Regex("\\s+")).filter { it.isNotBlank() }
            val css = classes.joinToString("") { ".$it" }
            return applyPosition(
                current.flatMap { it.select(css).toList() },
                position
            )
        }
        // class.item!0 写法：排除位置直接跟在名称后
        if (parts.size == 2 && name.contains("!")) {
            val bang = name.indexOf('!')
            position = name.substring(bang)
            name = name.substring(0, bang)
        }

        val selected = when (type) {
            "class" -> current.flatMap { it.getElementsByClass(name).toList() }
            // CSS 类名直接写法：.row.2 / .row!0 / .itemBox
            "id" -> current.mapNotNull { it.getElementById(name) }
            "tag" -> current.flatMap { it.getElementsByTag(name).toList() }
            "text" -> current.flatMap { el ->
                el.getAllElements().filter { it.ownText().contains(name) }
            }
            "children" -> current.flatMap { it.children().toList() }
            "all" -> current.flatMap { it.getAllElements().toList() }
            else -> emptyList()
        }
        return applyPosition(selected, position)
    }

    private fun applyPosition(elements: List<Element>, position: String): List<Element> {
        if (elements.isEmpty() || position.isBlank()) return elements
        if (position.startsWith("!")) {
            val excluded = position.substring(1)
                .split(":")
                .mapNotNull { it.trim().toIntOrNull() }
                .map { if (it < 0) elements.size + it else it }
                .toSet()
            return elements.filterIndexed { index, _ -> index !in excluded }
        }
        val index = position.toIntOrNull() ?: return elements
        val realIndex = if (index < 0) elements.size + index else index
        if (realIndex < 0 || realIndex >= elements.size) return emptyList()
        return listOf(elements[realIndex])
    }

    private fun selectBranchElements(root: Element, rule: String): List<Element> {
        val r = rule.trim()
        if (r.startsWith("@css:")) {
            val css = r.removePrefix("@css:").trim().substringBefore('@')
            if (css.isBlank()) return emptyList()
            return try { root.select(css).toList() } catch (e: Exception) { emptyList() }
        }
        val firstSegment = r.substringBefore('@').trim()
        if (looksLikeLegadoSegments(firstSegment)) {
            val segments = r.split("@").map { it.trim() }.filter { it.isNotBlank() }
            var current: List<Element> = listOf(root)
            for (seg in segments) {
                if (isContentSegment(seg)) break
                current = applySegment(current, seg)
                if (current.isEmpty()) return emptyList()
            }
            return current
        }
        // 旧格式兼容：纯 CSS 列表
        val css = r.substringBefore('@').trim().ifBlank { return emptyList() }
        return try { root.select(css).toList() } catch (e: Exception) { emptyList() }
    }

    private fun looksLikeLegadoSegments(firstSegment: String): Boolean {
        if (firstSegment == "children" || firstSegment.startsWith("children.")) return true
        // .row.2 / .row!0 / .itemBox 按 class 段处理
        if (firstSegment.startsWith(".")) {
            val after = firstSegment.substring(1)
            return after.isNotEmpty() && !after.contains(" ")
        }
        val type = firstSegment.substringBefore('.').lowercase()
        if (type in segmentTypes && firstSegment.contains('.')) return true
        // Legado 简写：a.0 / img / button.0 等常见标签
        if (type in commonTags) {
            val rest = firstSegment.substringAfter('.', "")
            // 仅当第二段是位置（数字/!排除）或整体是单标签时按 tag 处理
            return rest.isEmpty() || rest.matches(Regex("^-?\\d+.*$")) || rest.startsWith("!")
        }
        return false
    }

    private fun extractContent(element: Element, content: String): String? {
        val c = content.trim()
        val value = when (c.lowercase()) {
            "text" -> element.text()
            "owntext" -> element.ownText()
            "textnodes" -> element.textNodes().joinToString("\n") { it.text() }
            "html" -> element.html()
            "all" -> element.outerHtml()
            else -> element.attr(c)
        }
        return value.trim().ifBlank { null }
    }

    private fun splitRegexReplacement(rule: String): Pair<String, RegexReplacement?> {
        if (!rule.contains("##")) return rule to null
        val parts = rule.split("##")
        if (parts.size == 2) {
            return parts[0].trim() to RegexReplacement(parts[1], "")
        }
        val base = parts[0].trim()
        val regex = parts[1]
        val replacement = parts.drop(2).joinToString("##")
        return base to RegexReplacement(regex, replacement)
    }

    private fun applyRegexReplacement(value: String, info: RegexReplacement?): String {
        if (info == null) return value
        return try {
            Regex(info.regex).replace(RuleBudget.text(value), info.replacement)
        } catch (e: StackOverflowError) {
            throw IllegalArgumentException("书源正则回溯过深", e)
        }
    }

    private data class RegexReplacement(val regex: String, val replacement: String)

    /** 便捷方法：在 Document 上按规则取第一个值。 */
    fun evalFirstOnDocument(doc: Document, rawRule: String): String? =
        evalFirst(doc, rawRule)

    /** 便捷方法：在 Document 上选取列表元素。 */
    fun selectElementsOnDocument(doc: Document, rawRule: String): List<Element> =
        selectElements(doc, rawRule)
}
