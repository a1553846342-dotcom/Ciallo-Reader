/** @type {import('../venera/_vener_.js')} */
class BiliManga extends ComicSource {
    // 依据 keiyoushi/extensions-source 的 BiliManga.kt 契约移植（2026-08 #18140/#18556 后版本）：
    // - 搜索需 search_guard 三步换取 jieqiSearchTicket cookie，缺凭证时给出明确错误；
    // - 章节目录存在 javascript:cid(1) 占位链接：真实地址 = 相邻章节页里的
    //   url_previous/url_next JS 变量指向的 ..._2.html（配 night=1 cookie 可读）；
    // - 详情 author 取 .illname（画师名），.authorname 是漫画家，两者别写反。

    name = "嗶哩漫畫"

    key = "bilimanga"

    version = "1.1.2"

    minAppVersion = "1.6.0"

    url = "https://www.bilimanga.net"

    baseUrl = "https://www.bilimanga.net"

    headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
        "Accept": "*/*",
        "Referer": "https://www.bilimanga.net/",
        "Accept-Language": "zh"
    }

    readerHeaders() {
        return Object.assign({}, this.headers, {
            "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        });
    }

    async page(url, headers) {
        const res = await Network.get(url, headers || this.headers);
        if (res.status !== 200) throw new Error("哔哩页面返回 " + res.status);
        if (typeof res.body !== "string" || !res.body.trim()) throw new Error("哔哩页面返回空响应，请重试");
        return res;
    }

    async cookieHeader() {
        // 显式组装 Cookie 头：不依赖宿主自动携带，App 与桌面 harness 行为一致
        const cookies = await Network.getCookies(this.baseUrl);
        return (cookies || []).map(c => `${c.name}=${c.value}`).join("; ");
    }

    hasCookie(names, want) {
        return names.indexOf(want) !== -1;
    }

    async ensureSearchTicket() {
        const cookies = await Network.getCookies(this.baseUrl);
        const names = (cookies || []).map(c => c.name);
        const freshGuards = this.hasCookie(names, "jieqiSearchCss") && this.hasCookie(names, "jieqiSearchJs") &&
            this.searchGuardsReadyAt && Date.now() - this.searchGuardsReadyAt < 10 * 60 * 1000;
        // The host's legacy cookie store does not keep expiry times. Refresh old guards
        // together; their mere presence does not mean they are still accepted by the site.
        if (!freshGuards) {
            const [cssRes, jsRes] = await Promise.all([
                Network.get(`${this.baseUrl}/search.html?search_guard=css`, this.headers),
                Network.get(`${this.baseUrl}/search.html?search_guard=js`, this.headers)
            ]);
            if (cssRes.status !== 200 || jsRes.status !== 200) throw "搜索凭证请求失败，请稍后重试";
            if (typeof jsRes.body !== "string" || !jsRes.body.trim()) throw new Error("哔哩搜索凭证返回空响应，请重试");
            const m = jsRes.body.match(/cookie="(.*?)";/);
            if (m) {
                // 形如 "名字=值; path=/"，取第一段
                const pair = m[1].split(";")[0];
                const eq = pair.indexOf("=");
                if (eq > 0) {
                    await Network.setCookies(this.baseUrl, [{
                        name: pair.slice(0, eq).trim(),
                        value: pair.slice(eq + 1).trim(),
                        domain: "www.bilimanga.net"
                    }]);
                }
            }
            this.searchGuardsReadyAt = Date.now();
        }
        // The ticket is consumed by each search. Only the CSS/JS guards may be reused.
        const redeemed = await Network.get(`${this.baseUrl}/search.html?search_guard=redeem`,
            Object.assign({}, this.headers, { "Cache-Control": "no-cache" }));
        const after = (await Network.getCookies(this.baseUrl) || []).map(c => c.name);
        if (redeemed.status !== 200 || !this.hasCookie(after, "jieqiSearchTicket")) {
            throw "獲取搜索憑證失敗，請稍後重試";
        }
    }

    search = {
        load: async (keyword, options, page) => {
            await this.ensureSearchTicket();
            const cookie = await this.cookieHeader();
            const res = await Network.get(
                `${this.baseUrl}/search/${encodeURIComponent(keyword)}_${page}.html`,
                Object.assign({}, this.headers, cookie ? { Cookie: cookie } : {})
            );
            if (res.status !== 200) throw `搜索失败: ${res.status}`;
            if (typeof res.body !== "string" || !res.body.length) throw "搜索响应为空，请稍后重试";
            return this.parseSearch(res, page);
        }
    }

    parseSearch(res, page) {
            const doc = new HtmlDocument(res.body);
            try {
            // A single match redirects to its own detail page. Recommended books
            // elsewhere in that document must never supply the selected book ID.
            const detail = doc.querySelector("#bookDetailWrapper, .page-book-detail");
            if (detail) {
                const title = doc.querySelector("#bookDetailWrapper .book-title, .book-detail-info .book-title");
                const cover = doc.querySelector(".book-cover");
                const target = (res.url || "").match(/\/detail\/(\d+)\.html(?:[?#]|$)/);
                const coverId = ((cover && cover.attributes.src) || "").match(/\/(\d+)\/\1s\./);
                const id = target ? target[1] : (coverId ? coverId[1] : "");
                if (!id || !title) throw "搜索详情未返回有效漫画标识，请重试";
                return { comics: [new Comic({ id: this.baseUrl + "/detail/" + id + ".html",
                    title: title.text.trim(), cover: cover ? this.absUrl(cover.attributes.src || "") : "", tags: [] })], maxPage: 1 };
            }
            if (res.url && !/\/search(?:\/|\.html)/.test(res.url)) throw "站点未返回搜索结果，请稍后重试";
            const comics = [];
            for (const el of doc.querySelectorAll('a.book-layout[href*="/detail/"]')) {
                const img = el.querySelector("img");
                const href = el.attributes["href"] || "";
                if (!href) continue;
                comics.push(new Comic({
                    id: this.absUrl(href),
                    title: img ? (img.attributes["alt"] || "") : "",
                    cover: this.absUrl(img ? (img.attributes["data-src"] || img.attributes["src"] || "") : ""),
                    tags: []
                }));
            }
            let maxPage = page;
            const pageEl = doc.querySelector("#pagelink > span");
            if (pageEl) {
                const m = pageEl.text.match(/第(\d+)\/(\d+)页/);
                if (m) maxPage = parseInt(m[2]);
            }
            return { comics, maxPage };
            } finally { doc.dispose(); }
    }

    absUrl(href) {
        if (!href || href.startsWith("javascript")) return "";
        if (href.startsWith("http")) return href;
        if (href.startsWith("//")) return "https:" + href;
        if (href.startsWith("/")) return this.baseUrl + href;
        return this.baseUrl + "/" + href;
    }

    toHalfWidthDigits(s) {
        let out = "";
        for (const ch of s) {
            const code = ch.charCodeAt(0);
            out += (code >= 0xFF10 && code <= 0xFF19) ? String.fromCharCode(code - 65248) : ch;
        }
        return out;
    }

    // 目录条目解析：javascript:cid(1) 占位链接按相邻条目补 fragment，
    // loadEp 会在请求时用相邻页的 url_previous/url_next 变量解析真实地址。
    catalogLinks(doc) {
        const els = doc.querySelectorAll(".chapter-li-a");
        const out = [];
        for (let i = 0; i < els.length; i++) {
            const raw = els[i].attributes["href"] || "";
            const title = this.toHalfWidthDigits(els[i].text.trim());
            if (raw && !raw.startsWith("javascript")) {
                const href = this.absUrl(raw);
                if (href) out.push({ href, title });
                continue;
            }
            // javascript:cid(1) 占位：首条借用下一条的 href + #prev，其余借上一条 + #next
            let fallback = "";
            if (i === 0 && els.length > 1) {
                const nextRaw = els[1].attributes["href"] || "";
                if (nextRaw && !nextRaw.startsWith("javascript")) fallback = this.absUrl(nextRaw) + "#prev";
            } else if (i > 0) {
                const prevRaw = els[i - 1].attributes["href"] || "";
                if (prevRaw && !prevRaw.startsWith("javascript")) fallback = this.absUrl(prevRaw) + "#next";
            }
            if (fallback) out.push({ href: fallback, title });
        }
        return out;
    }

    comic = {
        loadInfo: async (id) => {
            const url = id.startsWith("http") ? id : this.absUrl(id);
            const cookie = await this.cookieHeader();
            const res = await this.page(url, Object.assign({}, this.headers, cookie ? { Cookie: cookie } : {}));
            if (res.status !== 200) throw `加载详情失败: ${res.status}`;
            const doc = new HtmlDocument(res.body);
            const verifyForm = doc.querySelector(".aui-ver-form");
            if (verifyForm) throw verifyForm.text.trim() || "该作品需要年龄验证";
            const titleEl = doc.querySelector(".book-title");
            const coverEl = doc.querySelector(".book-cover");
            // 上游契约：author = .illname（画师），artist = .authorname；为空时互为回退
            const illEl = doc.querySelector(".illname");
            const authEl = doc.querySelector(".authorname");
            const author = (illEl && illEl.text.trim()) || (authEl && authEl.text.trim()) || "";
            const descEl = doc.querySelector("#bookSummary > content");
            const metaStatus = [];
            for (const em of doc.querySelectorAll(".book-meta em")) {
                const t = em.text.trim();
                if (t === "連載中" || t === "已完結") metaStatus.push(t);
            }
            const genres = [];
            for (const g of doc.querySelectorAll(".tag-small")) genres.push(g.text.trim());
            const m = url.match(/\/detail\/(\d+)\.html/);
            const mangaId = m ? m[1] : "";
            const chapters = {};
            if (mangaId) {
                const cres = await this.page(`${this.baseUrl}/read/${mangaId}/catalog`, this.headers);
                if (cres.status !== 200) throw `加载目录失败: ${cres.status}`;
                if (cres.status === 200) {
                    const cdoc = new HtmlDocument(cres.body);
                    const list = this.catalogLinks(cdoc).slice().reverse();
                    for (const item of list) {
                        chapters[item.href] = item.title;
                    }
                }
            }
            if (!Object.keys(chapters).length) throw "源站未提供可读取的章节目录";
            return new ComicDetails({
                title: titleEl ? titleEl.text.trim() : "",
                cover: coverEl ? this.absUrl(coverEl.attributes["src"] || "") : "",
                description: descEl ? descEl.text.trim() : "",
                author: author,
                status: metaStatus.length ? metaStatus[metaStatus.length - 1] : null,
                chapters: chapters,
                tags: {
                    "作者": author ? [author] : [],
                    "分類": genres
                }
            });
        },

        loadEp: async (comicId, epId) => {
            await Network.setCookies(this.baseUrl, [{name:"night", value:"1", domain:"www.bilimanga.net"}]);
            // 占位章节的 epId 带 #prev/#next fragment：
            // 先取相邻章节页，从其 url_previous/url_next JS 变量解析真实 ..._2.html 地址
            let url = epId.startsWith("http") ? epId : this.absUrl(epId);
            const fragMatch = url.match(/#(prev|next)$/);
            if (fragMatch) {
                const base = url.slice(0, url.length - fragMatch[0].length);
                const neighbor = await this.page(base, this.readerHeaders());
                if (neighbor.status !== 200) throw `加载章节失败: ${neighbor.status}`;
                const re = fragMatch[1] === "prev" ? /url_previous:'(.*?)'/ : /url_next:'(.*?)'/;
                const m = neighbor.body.match(re);
                let path = m ? m[1] : "";
                if (!path) {
                    // 变量缺失时按章节 id ±1 预测（上游 predictUrlByContext 同款）
                    const cm = base.match(/\/read\/(\d+)\/(\d+)\.html/);
                    if (!cm) throw "章节鏈接错误";
                    const step = fragMatch[1] === "prev" ? -1 : 1;
                    path = `/read/${cm[1]}/${parseInt(cm[2]) + step}.html`;
                }
                url = this.absUrl(path.replace(".", "_2."));
            }
            const cookie = await this.cookieHeader();
            const nightCookie = cookie || "night=1";
            const res = await this.page(url, Object.assign({}, this.readerHeaders(), { Cookie: nightCookie }));
            if (res.status !== 200) throw `加载章节失败: ${res.status}`;
            const doc = new HtmlDocument(res.body);
            const images = [];
            for (const img of doc.querySelectorAll(".imagecontent")) {
                const u = img.attributes["data-src"] || img.attributes["src"] || "";
                if (u) images.push(this.absUrl(u));
            }
            if (!images.length) {
                const tip = doc.querySelector("#acontentz");
                if (tip && tip.text.indexOf("電腦端") >= 0) throw "该站点不支持电脑端 UA 阅读";
                throw "未取到章节图片（可能已下架或需要权限）";
            }
            return { images };
        },

        onImageLoad: (url, comicId, epId) => {
            return {
                headers: Object.assign({}, this.readerHeaders(), { Referer: this.baseUrl + "/" })
            };
        }
    }
}
