package com.example.source.js

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Venera HtmlDocument/HtmlElement 桥接的 DOM 存储：
 * JS 侧持有一个整数 key，所有查询与属性读取都回到这里用 Jsoup 完成。
 */
class JsHtmlStore {
    // JS 侧 HtmlDocument._key 从 0 开始，这里必须一致，否则第一个文档永远查不到
    private var nextId = 0
    private val docs = LinkedHashMap<Int, Document>(16, 0.75f, true)
    private val elements = HashMap<Int, Element>()
    private val nodes = HashMap<Int, Node>()
    private val documentIds = java.util.IdentityHashMap<Document, Int>()
    private val handleIds = java.util.IdentityHashMap<Node, Int>()
    private val owners = HashMap<Int, Int>()
    private val sizes = HashMap<Int, Long>()

    private fun next(): Int = nextId++

    /**
     * 文档 key 由 JS 侧 HtmlDocument._key 提供（0,1,2...），
     * 不能与元素 id 共用自增计数器，否则后续文档全部错位。
     */
    fun parse(jsKey: Int, html: String): Int {
        require(html.length <= 2 * 1024 * 1024) { "书源 HTML 超过 2Mi 字符" }
        dispose(jsKey)
        val doc = Jsoup.parse(html)
        val size = html.length * 2L + doc.getAllElements().size * 512L
        require(size <= 4L * 1024 * 1024) { "书源 HTML 解析内存预算不足" }
        docs[jsKey] = doc
        documentIds[doc] = jsKey
        sizes[jsKey] = size
        // Every selected element keeps its entire Jsoup document alive. Bound the
        // retained documents AND release their handles, rather than clearing docs alone.
        while (docs.size > 8 || (docs.size > 1 && sizes.values.sum() > 4L * 1024 * 1024)) {
            dispose(docs.keys.first())
        }
        return jsKey
    }

    fun dispose(docId: Int) {
        docs.remove(docId)?.let { documentIds.remove(it) }
        sizes.remove(docId)
        val handles = owners.filterValues { it == docId }.keys
        handles.forEach { id ->
            nodes.remove(id)?.let { handleIds.remove(it) }
            elements.remove(id)
            owners.remove(id)
        }
    }

    fun clear() {
        docs.clear()
        elements.clear()
        nodes.clear()
        documentIds.clear()
        handleIds.clear()
        owners.clear()
        sizes.clear()
    }

    fun querySelector(docId: Int, query: String): Int? {
        val doc = docs[docId] ?: return null
        return doc.selectFirst(query)?.let { elementId(it) }
    }

    fun querySelectorAll(docId: Int, query: String): List<Int> {
        val doc = docs[docId] ?: return emptyList()
        return doc.select(query).map { elementId(it) }
    }

    fun getElementById(docId: Int, id: String): Int? {
        val doc = docs[docId] ?: return null
        return doc.getElementById(id)?.let { elementId(it) }
    }

    fun domQuerySelector(elemId: Int, query: String): Int? {
        val el = elements[elemId] ?: return null
        return el.selectFirst(query)?.let { elementId(it) }
    }

    fun domQuerySelectorAll(elemId: Int, query: String): List<Int> {
        val el = elements[elemId] ?: return emptyList()
        return el.select(query).map { elementId(it) }
    }

    fun getText(elemId: Int): String {
        val el = elements[elemId] ?: return ""
        val text = el.text()
        if (text.isNotBlank()) return text
        // script/style 等标签内容在 Jsoup 中是 DataNode，text() 取不到；
        // Venera(html 包) 会把 script 内容当文本返回，这里兼容
        val tag = el.tagName().lowercase()
        if (tag == "script" || tag == "style") {
            return el.data().trim()
        }
        if (el.childNodes().isNotEmpty() &&
            el.childNodes().all { it is org.jsoup.nodes.DataNode }
        ) {
            return el.data().trim()
        }
        return ""
    }

    fun getAttributes(elemId: Int): Map<String, String> {
        val el = elements[elemId] ?: return emptyMap()
        val attrs = LinkedHashMap<String, String>()
        el.attributes().forEach { attrs[it.key] = it.value }
        return attrs
    }

    fun getChildren(elemId: Int): List<Int> =
        elements[elemId]?.children()?.map { elementId(it) } ?: emptyList()

    fun getNodes(elemId: Int): List<Int> =
        elements[elemId]?.childNodes()?.map { nodeId(it) } ?: emptyList()

    fun getInnerHTML(elemId: Int): String = elements[elemId]?.html() ?: ""

    fun getParent(elemId: Int): Int? = elements[elemId]?.parent()?.let { elementId(it) }

    fun getClassNames(elemId: Int): List<String> =
        elements[elemId]?.classNames()?.toList() ?: emptyList()

    fun getId(elemId: Int): String? =
        elements[elemId]?.id()?.takeIf { it.isNotBlank() }

    fun getLocalName(elemId: Int): String = elements[elemId]?.tagName() ?: ""

    fun getPreviousSibling(elemId: Int): Int? =
        elements[elemId]?.previousElementSibling()?.let { elementId(it) }

    fun getNextSibling(elemId: Int): Int? =
        elements[elemId]?.nextElementSibling()?.let { elementId(it) }

    fun nodeText(nodeId: Int): String = nodes[nodeId]?.toString() ?: ""

    fun nodeType(nodeId: Int): String {
        return when (nodes[nodeId]) {
            is Element -> "element"
            is TextNode -> "text"
            is Comment -> "comment"
            is Document -> "document"
            else -> "unknown"
        }
    }

    fun nodeToElement(nodeId: Int): Int? {
        val n = nodes[nodeId] as? Element ?: return null
        return elementId(n)
    }

    private fun elementId(el: Element): Int {
        handleIds[el]?.let { return it }
        val id = next()
        elements[id] = el
        retain(id, el)
        return id
    }

    private fun nodeId(n: Node): Int {
        handleIds[n]?.let { return it }
        if (n is Element) return elementId(n)
        val id = next()
        retain(id, n)
        return id
    }

    private fun retain(id: Int, node: Node) {
        nodes[id] = node
        handleIds[node] = id
        node.ownerDocument()?.let { documentIds[it] }?.let { owners[id] = it }
    }
}
