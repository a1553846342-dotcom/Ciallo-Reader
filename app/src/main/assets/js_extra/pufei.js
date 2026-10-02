/** Public reader/API adapter for https://www.pufeimh.com/. */
class Pufei extends ComicSource {
    name = "扑飞漫画";
    key = "pufei";
    version = "1.0.5";
    minAppVersion = "1.6.0";
    url = "https://www.pufeimh.com";
    baseUrl = "https://www.pufeimh.com";
    headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
        "Referer": "https://m.pufeimh.com/category"
    };

    abs(path, origin = this.baseUrl) {
        path = String(path || "").trim();
        if (/^https?:\/\//i.test(path)) return path.replace(/^http:(\/\/(?:www|m)\.(?:pufeimh\.com|guoman\.net)(?=\/|$))/i, "https:$1");
        if (path.startsWith("//")) return "https:" + path;
        return origin + (path.startsWith("/") ? path : "/" + path);
    }

    pageKey(url) {
        if (String(url).startsWith("https://v2.apikk.top/api/v2/chapter/getinfo?")) return String(url);
        if (String(url).startsWith("https://manwaxu.cc/api/comic/image/")) return String(url);
        return this.abs(url).replace(/^https?:\/\/(?:www|m)\.pufeimh\.com/i, "").split(/[?#]/)[0];
    }

    alternatePage(url) {
        const match = this.abs(url).match(/^https:\/\/(www|m)\.(pufeimh\.com|guoman\.net)(\/[^?#]*)(?:[?#].*)?$/i);
        return match ? "https://" + (match[1].toLowerCase() === "www" ? "m" : "www") + "." + match[2] + match[3] : null;
    }

    cachedPages() {
        const stored = this.loadData("reader_pages_v3");
        return stored && Array.isArray(stored) ? stored : [];
    }

    rememberPages(key, images) {
        if (images.length > 2000) return;
        const now = Date.now();
        const stored = this.cachedPages().filter(v => v && v.key !== key && now - v.at < 10 * 60 * 1000);
        stored.unshift({ key, at: now, images });
        let count = 0;
        this.saveData("reader_pages_v3", stored.filter((v, i) => {
            count += Array.isArray(v.images) ? v.images.length : 2001;
            return i < 8 && count <= 2000;
        }));
    }

    async json(url) {
        const r = await this.request(url, this.headers);
        if (!r || r.status !== 200) throw new Error("扑飞接口暂时无法连接，请重试");
        if (typeof r.body !== "string" || !r.body.trim()) throw new Error("扑飞接口返回空响应，请重试");
        let text = r.body.trim();
        if (text.startsWith("pufei(")) text = text.slice(6, text.lastIndexOf(")"));
        const data = JSON.parse(text);
        if (data.code !== 1 || !Array.isArray(data.data)) throw new Error(data.msg || "扑飞接口未返回列表");
        return data.data;
    }

    initial(text) {
        const first = text.charAt(0).toUpperCase();
        if (/^[A-Z]$/.test(first)) return first;
        const bytes = Array.from(new Uint8Array(Convert.encodeGbk(first)));
        if (bytes.length !== 2) return "";
        const code = bytes[0] * 256 + bytes[1] - 65536;
        const limits = [-20319,-20284,-19776,-19219,-18711,-18527,-18240,-17923,-17418,-16475,-16213,-15641,-15166,-14923,-14915,-14631,-14150,-14091,-13319,-12839,-12557,-11848,-11056];
        const letters = "ABCDEFGHJKLMNOPQRSTWXYZ";
        for (let i = limits.length - 1; i >= 0; i--) if (code >= limits[i]) return letters.charAt(i);
        return "";
    }

    catalogueUrl(mark, page) {
        // This site's older MCCMS API requires a category type and JSONP callback.
        // Its keyword endpoint currently rejects requests; use its public initial filter.
        return this.baseUrl + "/index.php/api/data/comic?callback=pufei&size=300&page=" + page +
            "&type%5Border%5D=hits" + (mark ? "&type%5Bmark%5D=" + encodeURIComponent(mark) : "");
    }

    compact(items) {
        return items.filter(b => b.chapter_nums === undefined || Number(b.chapter_nums) > 0).map(b => [this.titleText(b.name), b.url,
            b.pic_local ? this.abs(b.pic_local) : this.abs(b.pic || ""), b.author || "", b.chapter_url || "",
            b.source_url || ""]);
    }

    titleText(value) {
        return String(value || "").replace(/&#(\d+);?/g, (_, n) => String.fromCharCode(Number(n)))
            .replace(/&middot;?/g, "·").replace(/&amp;/g, "&").replace(/&quot;/g, '"');
    }

    workName(value) {
        // Only explicit editions and published aliases, never fuzzy title matches.
        const title = this.titleText(value).trim().replace(/（/g, "(").replace(/）/g, ")")
            .replace(/【\d+后搜：[^】]+】$/, "(旧)").replace(/\((?:旧版|舊版|舊)\)$/, "(旧)");
        return ["海贼王", "航海王(海贼王)"].includes(title) ? "航海王" : title;
    }

    rememberSearchLinks(items) {
        const stored = this.loadData("book_links_v4");
        const links = new Map((Array.isArray(stored) ? stored : []).filter(v => v && v.key).map(v => [v.key, v]));
        for (const b of items) {
            if (!b[1] || !b[4]) continue;
            const key = this.pageKey(b[1]);
            const previous = links.get(key);
            links.delete(key);
            const slug = String(b[5] || "").match(/^https?:\/\/(?:goda|bz)\.mh\.com\/(?:manga|comic)\/([\w-]+)$/);
            links.set(key, { key, title: b[0], cover: b[2] || previous?.cover || "", author: b[3] || previous?.author || "", chapter: this.abs(b[4]),
                supplier: slug ? slug[1] : previous && previous.supplier,
                publicSupplier: /^https?:\/\/manwa\.mh\.com\/book\/\d+$/.test(String(b[5] || "")) || previous?.publicSupplier || false,
                mirror: /^https?:\/\/dmw\.mh\.com\//.test(String(b[5] || "")) && key.startsWith("/comic/")
                    ? "https://www.guoman.net" + key : previous?.mirror });
        }
        // Some older aliases only list a discontinued upstream, while another
        // card for the exact same title has the current public supplier.
        const suppliers = new Map();
        for (const v of links.values()) if (v.supplier) suppliers.set(this.workName(v.title), v.supplier);
        if (suppliers.size) for (const v of links.values()) if (!v.supplier)
            v.supplier = suppliers.get(this.workName(v.title));
        const mirrors = new Map();
        for (const v of links.values()) if (v.mirror) mirrors.set(this.workName(v.title), v.mirror);
        if (mirrors.size) for (const v of links.values()) if (!v.mirror)
            v.mirror = mirrors.get(this.workName(v.title));
        this.saveData("book_links_v4", Array.from(links.values()).slice(-128));
    }

    matches(items, keyword) {
        const unique = new Map();
        const matched = [];
        for (const b of items) {
            if (!b[0].toLowerCase().includes(keyword.toLowerCase()) || !b[1]) continue;
            matched.push(b);
            if (!unique.has(b[1])) {
                unique.set(b[1], new Comic({
                id: this.abs(b[1]), title: b[0], cover: b[2], subTitle: b[3], tags: []
                }));
            }
        }
        if (matched.length) this.rememberSearchLinks(matched);
        return Array.from(unique.values());
    }

    search = {
        load: async (keyword, options, page) => {
            keyword = keyword.trim().replace("我家娘子来自一千年前", "我家老婆来自一千年前");
            if (!keyword) return { comics: [], maxPage: 1 };
            const mark = this.initial(keyword);
            const cacheKey = "catalogue_v4_" + (mark || "popular");
            const cached = this.loadData(cacheKey);
            if (cached && Date.now() - cached.at < 6 * 60 * 60 * 1000) {
                const comics = this.matches(cached.items, keyword);
                if (comics.length || cached.complete) return { comics, maxPage: 1 };
            }
            let items = [];
            // The first 300 titles contain the popular matches; do not make readers wait
            // for the entire catalogue. Only continue paging when no match is found.
            for (let p = 1; p <= 16; p++) {
                const rows = await this.json(this.catalogueUrl(mark, p));
                items.push(...this.compact(rows));
                const comics = this.matches(items, keyword);
                const complete = rows.length < 300;
                if (comics.length || complete || p === 16) {
                    this.saveData(cacheKey, { at: Date.now(), items, complete });
                    return { comics, maxPage: 1 };
                }
            }
        }
    };

    async request(url, headers) {
        // alpha13's rejection tracker treats even caught async rejections as
        // fatal. Consume the host's structured HTTP result before Network.get
        // turns a recoverable transport error into a rejected promise.
        let r = await sendMessage({method: "http", http_method: "GET", url, headers});
        if (typeof r === "string") {
            try { r = JSON.parse(r); } catch (_) { return null; }
        }
        return r && !r.error ? r : null;
    }

    async html(url) {
        // The host decodes UTF-8/GBK directly; avoid converting the entire HTML
        // through Base64 and the JS byte-buffer bridge on mobile devices.
        const headers = /^https:\/\/(?:www|m)\.guoman\.net\//.test(String(url))
            ? { ...this.headers, "Referer": "https://www.guoman.net/" } : this.headers;
        const r = await this.request(url, headers);
        if (!r || r.status !== 200 || typeof r.body !== "string" || !r.body.trim()) return null;
        return r.body;
    }

    originalImage(url) {
        // The site's relay embeds the original public image URL. Avoid the relay's
        // extra hop and use the original reader's referer for its CDN.
        if (/^https:\/\/s[12]\.325784\.xyz\//.test(url)) {
            const value = Convert.decodeUtf8(Convert.decodeBase64(decodeURIComponent(url.split("/").pop())));
            if (/^https:\/\//.test(value)) url = value;
        }
        // The supplier's current public reader serves scomic pages as WebP.
        // Pufei's older catalogue still points at JPG files, which now 404.
        if (/^https:\/\/[ct]-nd[23]-1\.6wm\.top\/scomic\//.test(url))
            return url.replace(/\.jpg(?=\?|$)/i, ".webp");
        return url;
    }

    parseDetails(text, origin = this.baseUrl) {
        if (typeof text !== "string" || !text) return null;
        const doc = new HtmlDocument(text);
        try {
            // The mobile site has no detail-info-title or chapterlistload at all.
            const title = doc.querySelector(".detail-info-title, .detail-main-title");
            const cover = doc.querySelector(".detail-info-cover, .detail-bar-img");
            const desc = doc.querySelector(".detail-info-content, .detail-main-content");
            const author = doc.querySelector(".detail-info-tip a, .detail-main-subtitle .block a");
            const metaTitle = doc.querySelector('meta[property="og:title"]');
            const pageTitle = doc.querySelector("title");
            const name = String((title ? title.text : "") || (metaTitle ? metaTitle.attributes.content : "") || (pageTitle ? pageTitle.text : "") || "")
                .replace(/漫画全集\s*[-–|].*$/, "").trim();
            const chapters = {};
            // Do not run an unanchored regexp across full HTML in QuickJS.
            // Reader scripts can contain hundreds of KB of encrypted image data.
            const hits = text.indexOf("/api/hits/comic/");
            const comic = hits < 0 ? null : text.slice(hits, hits + 48).match(/\/api\/hits\/comic\/(\d+)/);
            // Only catalogue containers: a recommendation/start-reading link is
            // not a complete catalogue. Absolute mobile URLs are also legitimate.
            for (const a of doc.querySelectorAll("#chapterlistload a[href], .detail-list .detail-list-item a[href]")) {
                const href = this.abs(a.attributes.href, origin);
                const match = href.match(/^https:\/\/(?:(?:www|m)\.pufeimh\.com|www\.guoman\.net)\/chapter\/(\d+)-\d+\.html(?:[?#].*)?$/i);
                const label = a.text.trim();
                if (!match || !label || (comic && match[1] !== comic[1])) continue;
                // Keep one stable chapter URL across desktop/mobile layouts.
                const canonical = origin + href.slice(href.indexOf("/chapter/")).split(/[?#]/)[0];
                if (!chapters[canonical]) chapters[canonical] = label;
            }
            if (!name || !Object.keys(chapters).length) return null;
            const entries = Object.entries(chapters);
            const numbered = entries.map(v => v[1].match(/^(?:第\s*)?(\d+)(?:\s*[话話章回集]|$)/)).filter(Boolean);
            // Desktop/mobile can serve opposite sort directions. Preserve one
            // reading order without sorting random chapter IDs or special titles.
            if (numbered.length > 1 && Number(numbered[0][1]) > Number(numbered[numbered.length - 1][1])) entries.reverse();
            return {
                title: name, cover: cover ? this.abs(cover.attributes["data-src"] || cover.attributes.src || "", origin) : "",
                description: desc ? desc.text.trim() : "", author: author ? author.text.trim() : "",
                chapters: Object.fromEntries(entries), tags: {}
            };
        } finally { doc.dispose(); }
    }

    readerPayload(text) {
        if (typeof text !== "string" || !text) return null;
        // A greedy capture of a long Base64 string exhausts regexp execution
        // memory on some QuickJS/device combinations. Scan delimiters instead.
        const identifier = c => c && ((c >= "a" && c <= "z") || (c >= "A" && c <= "Z") ||
            (c >= "0" && c <= "9") || c === "_" || c === "$");
        const whitespace = c => c === " " || c === "\t" || c === "\r" || c === "\n" || c === "\f";
        let offset = 0;
        while ((offset = text.indexOf("params", offset)) >= 0) {
            const token = offset;
            offset += 6;
            if (identifier(text[token - 1]) || identifier(text[offset])) continue;
            let start = offset;
            while (whitespace(text[start])) start++;
            if (text[start++] !== "=") continue;
            while (whitespace(text[start])) start++;
            const quote = text[start++];
            if (quote !== "'" && quote !== '"') continue;
            const end = text.indexOf(quote, start);
            // Check size before slicing, decoding or bridging bytes. Existing
            // one-MB decoded payload limit remains, without huge allocations.
            if (end < 0 || end - start > 1398104) return null;
            if (end - start < 24 || (end - start) % 4 !== 0) { offset = end + 1; continue; }
            let valid = true;
            for (let i = start; i < end; i++) {
                const c = text.charCodeAt(i);
                if ((c >= 65 && c <= 90) || (c >= 97 && c <= 122) || (c >= 48 && c <= 57) ||
                    c === 43 || c === 47 || (c === 61 && i >= end - 2)) continue;
                valid = false; break;
            }
            if (valid) return text.slice(start, end);
            offset = end + 1;
        }
        return null;
    }

    parseImages(text) {
        const payload = this.readerPayload(text);
        if (!payload) return null;
        const decoded = Convert.decodeBase64(payload);
        if (!decoded) return null;
        const bytes = Array.isArray(decoded) ? decoded : Array.from(new Uint8Array(decoded));
        if (bytes.length <= 16 || bytes.length > 1024 * 1024) return null;
        // Same public AES-CBC contract as pic-v2.js; never execute remote JS.
        const plain = Convert.decryptAesCbc(bytes.slice(16), Convert.encodeUtf8("9S8$vJnU2ANeSRoF"), bytes.slice(0,16));
        const params = JSON.parse(Convert.decodeUtf8(plain));
        if (!["www.pufeimh.com", "m.pufeimh.com", "www.guoman.net", "m.guoman.net"].includes(params.host) || !Array.isArray(params.images) ||
            !params.images.length ||
            params.images.some(url => typeof url !== "string" || !/^https?:\/\//i.test(url))) return null;
        return params.images.map(url => this.originalImage(url));
    }

    async supplierJson(path) {
        const r = await this.request("https://v2.apikk.top/api/v2/" + path, {
            "User-Agent": this.headers["User-Agent"], "Referer": "https://manhuafree.com/"
        });
        if (!r || r.status !== 200 || typeof r.body !== "string" || !r.body.trim()) return null;
        try {
            const result = JSON.parse(r.body);
            return result.status && result.data ? result.data : null;
        } catch (_) { return null; }
    }

    async supplierDetails(slug) {
        const text = await this.html("https://manhuafree.com/manga/" + slug);
        if (!text) return null;
        const doc = new HtmlDocument(text);
        let mid;
        try { mid = doc.querySelector("#mangachapters, #chaplistlast, #bookmarkData")?.attributes["data-mid"]; }
        finally { doc.dispose(); }
        if (!mid || !/^\d+$/.test(mid)) return null;
        // The supplier's default response only includes the first/last 10.
        // Use the same complete-catalogue mode as its public chapter drawer.
        const book = await this.supplierJson("manga/get?mid=" + mid + "&mode=all");
        if (!book?.title || !Array.isArray(book.chapters) || !book.chapters.length) return null;
        const chapters = {};
        for (const c of book.chapters) if (c.id && /^\d+$/.test(String(c.id)) && c.attributes?.title)
            chapters["https://v2.apikk.top/api/v2/chapter/getinfo?m=" + mid + "&c=" + c.id] = c.attributes.title;
        if (!Object.keys(chapters).length) return null;
        return { title: book.title, cover: book.cover || "", description: book.desc || "", chapters, tags: {} };
    }

    async currentSupplier(hint) {
        const name = this.workName(hint.title);
        const saved = this.loadData("supplier_resolutions_v1");
        const resolutions = Array.isArray(saved) ? saved : [];
        const cached = resolutions.find(v => v.name === name && Date.now() - v.at < 60 * 60 * 1000);
        const slug = cached?.slug || hint.supplier;
        if (slug) {
            try {
                const details = await this.supplierDetails(slug);
                if (details) return details;
            } catch (_) { /* A moved entry can still be found by its exact title. */ }
        }
        // Follow the supplier's actual public search form when old slugs moved.
        // Recommendations and merely similar titles cannot substitute the work.
        const text = await this.html("https://manhuafree.com/s/" + encodeURIComponent(name));
        if (!text) return null;
        const doc = new HtmlDocument(text);
        const candidates = [];
        try {
            for (const a of doc.querySelectorAll('a[href^="/manga/"]')) {
                const title = a.querySelector(".cardtitle");
                if (!title || this.workName(title.text) !== name) continue;
                const match = String(a.attributes.href).match(/^\/manga\/([\w-]+)$/);
                if (match && !candidates.includes(match[1]) && match[1] !== slug) candidates.push(match[1]);
            }
        } finally { doc.dispose(); }
        for (const candidate of candidates.slice(0, 3)) {
            try {
                const details = await this.supplierDetails(candidate);
                if (!details) continue;
                this.saveData("supplier_resolutions_v1", [{name, slug: candidate, at: Date.now()},
                    ...resolutions.filter(v => v.name !== name)].slice(0, 64));
                return details;
            } catch (_) { /* Try another exact-title entry, then the original site. */ }
        }
        return null;
    }

    decodeSupplierImages(text) {
        // Minimal, bounded implementation of the public reader's chapter
        // wire format. No remote script, anti-debug code or adverts execute.
        if (typeof text !== "string" || text.length > 1398104 ||
            !text.startsWith("J7r") || !text.endsWith("nQ")) return null;
        const body = text.slice(3, -2), length = body.length - 5;
        if (length < 0) return null;
        const tail = Math.floor(length / 3), first = Math.floor((length - tail) / 2);
        const second = length - tail - first;
        if (body.slice(first, first + 2) !== "kD" || body.slice(first + 2 + second, first + 5 + second) !== "W4s") return null;
        const mixed = body.slice(first + 5 + second) + body.slice(0, first) + body.slice(first + 2, first + 2 + second);
        let shuffled = "";
        for (let i = 0, chunk = 0; i < mixed.length; i += 7, chunk++) {
            const block = mixed.slice(i, i + 7);
            shuffled += chunk % 2 ? block.split("").reverse().join("") : block;
        }
        const from = "_-9876543210abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        const to = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let encoded = "";
        for (let i = 0; i < shuffled.length; i++) {
            const index = from.indexOf(shuffled[i]);
            if (index < 0) return null;
            encoded += to[index];
        }
        encoded += "=".repeat((4 - encoded.length % 4) % 4);
        const decoded = JSON.parse(Convert.decodeUtf8(Convert.decodeBase64(encoded)));
        return Array.isArray(decoded) ? decoded : null;
    }

    async supplierPages(url) {
        const query = url.slice("https://v2.apikk.top/api/v2/chapter/getinfo?".length);
        if (!/^m=\d+&c=\d+$/.test(query)) throw new Error("无效的扑飞供应章节地址");
        const data = await this.supplierJson("chapter/getinfo?" + query);
        const config = data?.info?.images;
        const images = Array.isArray(config?.images) ? config.images : this.decodeSupplierImages(config?.images);
        if (!images || !images.length) throw new Error("扑飞供应章节没有返回图片");
        const host = Number(config.line) === 2 ? "https://c-nd2-1.6wm.top" : "https://c-nd3-1.6wm.top";
        return images.map(v => {
            if (typeof v?.url !== "string" || !v.url.startsWith("/") || v.url.startsWith("//"))
                throw new Error("扑飞供应章节返回了无效图片地址");
            return host + v.url;
        });
    }

    async publicJson(path) {
        // This is the existing Manwaba source's migrated public API. Obsolete
        // manwa.mh.com book IDs do not match its current IDs; resolve by title.
        const r = await this.request("https://manwaxu.cc/api/" + path,
            { ...this.headers, "Referer": "https://manwaxu.cc/" });
        if (!r || r.status !== 200 || typeof r.body !== "string" || r.body.length > 2097152) return null;
        try { const data = JSON.parse(r.body); return data.code === 200 ? data : null; }
        catch (_) { return null; }
    }

    async publicDetails(hint) {
        if (!hint?.title) return null;
        const name = this.workName(hint.title);
        const stored = this.loadData("public_resolutions_v1");
        const resolutions = Array.isArray(stored) ? stored : [];
        const cached = resolutions.find(v => v.name === name && Date.now() - v.at < 60 * 60 * 1000);
        let id = cached?.id;
        if (!id) {
            const result = await this.publicJson("search?keyword=" + encodeURIComponent(name) + "&type=mh&page=1&pageSize=20");
            const list = result?.data?.list;
            if (!Array.isArray(list)) return null;
            const match = list.find(v => v && this.workName(v.title) === name && /^\d+$/.test(String(v.id)));
            if (!match) return null;
            id = String(match.id);
        }
        if (!/^\d+$/.test(String(id))) return null;
        const info = (await this.publicJson("comic/" + id))?.data;
        if (!info || this.workName(info.title) !== name || String(info.id) !== String(id)) return null;
        const chapters = {};
        let expected = null, received = 0;
        for (let page = 1; page <= 100; page++) {
            const result = await this.publicJson("comic/chapter?comicId=" + id + "&page=" + page + "&pageSize=200");
            if (!result || !Array.isArray(result.data)) return null;
            const total = Number(result.pagination?.total);
            if (!Number.isInteger(total) || total <= 0 || total > 20000 || (expected !== null && expected !== total)) return null;
            expected = total;
            if (!result.data.length) return null;
            for (const c of result.data) {
                // Only the API's freely readable chapters. Never substitute
                // a restricted chapter or quietly present a partial catalogue.
                if (!c || String(c.comicId) !== String(id) || !/^\d+$/.test(String(c.id)) ||
                    !c.title || c.isVip || Number(c.coin || 0) > 0) return null;
                chapters["https://manwaxu.cc/api/comic/image/" + c.id] = String(c.title);
            }
            received += result.data.length;
            if (received >= expected) break;
        }
        if (Object.keys(chapters).length !== expected) return null;
        this.saveData("public_resolutions_v1", [{name, id: String(id), at: Date.now()},
            ...resolutions.filter(v => v.name !== name)].slice(0, 64));
        // Keep Pufei's displayed cover: this supplier's new cover host also
        // encrypts bytes, whereas generic detail thumbnails expect an image.
        return {title: info.title, author: info.author || hint.author || "", cover: hint.cover || info.cover || "",
            description: info.intro || info.description || "", chapters,
            tags: {"阅读线路": ["漫蛙吧（备用公开线路）"]}};
    }

    async publicPages(url) {
        const id = url.slice("https://manwaxu.cc/api/comic/image/".length);
        if (!/^\d+$/.test(id)) throw new Error("无效的扑飞备用章节地址");
        const images = [];
        let expected = null;
        for (let page = 1; page <= 80; page++) {
            const result = await this.publicJson("comic/image/" + id + "?page=" + page +
                "&page_size=200&imageSource=" + encodeURIComponent("https://tu.mhttu.cc"));
            const data = result?.data;
            const total = Number(data?.pagination?.total);
            if (!Array.isArray(data?.images) || !data.images.length || !Number.isInteger(total) || total <= 0 ||
                total > 2000 || (expected !== null && expected !== total)) throw new Error("扑飞备用章节没有返回完整图片列表");
            expected = total;
            for (const image of data.images) {
                if (typeof image?.url !== "string" || !/^https:\/\/tu\.mhttu\.cc\//.test(image.url))
                    throw new Error("扑飞备用章节返回了无效图片地址");
                images.push(image.url);
            }
            if (images.length >= expected) break;
        }
        if (images.length !== expected || new Set(images).size !== expected)
            throw new Error("扑飞备用章节图片列表不完整");
        return images;
    }

    comic = {
        loadInfo: async (id) => {
            const key = this.pageKey(id);
            const saved = this.loadData("details_v4");
            // Same short freshness window as the host's chapter cache, also
            // available after recreating the source/opening the application.
            if (saved && saved.key === key && Date.now() - saved.at < 60 * 1000 && saved.data &&
                saved.data.chapters && Object.keys(saved.data.chapters).length) return new ComicDetails(saved.data);
            const url = this.abs(id);
            const links = this.loadData("book_links_v4");
            const hint = Array.isArray(links) ? links.find(v => v && v.key === key) : null;
            let details = null;
            const publicCache = this.loadData("public_resolutions_v1");
            if (hint && Array.isArray(publicCache) && publicCache.some(v =>
                v.name === this.workName(hint.title) && Date.now() - v.at < 60 * 60 * 1000))
                details = await this.publicDetails(hint);
            if (!details && hint?.supplier) {
                try {
                    details = await this.currentSupplier(hint);
                    if (details) details.author = hint.author || "";
                } catch (_) { /* Original public entry remains a fallback. */ }
            }
            if (!details && hint?.mirror) {
                try {
                    const candidate = this.parseDetails(await this.html(hint.mirror), "https://www.guoman.net");
                    if (candidate && this.workName(candidate.title) === this.workName(hint.title)) {
                        candidate.tags = { "阅读线路": ["爱国漫（备用公开线路）"] };
                        details = candidate;
                    }
                } catch (_) { /* An unavailable mirror cannot substitute another work. */ }
            }
            if (!details && (hint?.supplier || hint?.publicSupplier)) details = await this.publicDetails(hint);
            const entries = [url];
            if (hint?.chapter && this.pageKey(hint.chapter).startsWith("/chapter/")) entries.push(hint.chapter);
            const alternate = this.alternatePage(url);
            if (alternate) entries.push(alternate);
            for (const entry of Array.from(new Set(entries))) {
                if (details) break;
                // A 404 at an obsolete slug must not prevent its current public
                // chapter redirect or the other layout from being attempted.
                try { details = this.parseDetails(await this.html(entry)); } catch (_) {}
            }
            if (!details && hint && !hint.supplier && !hint.publicSupplier) details = await this.publicDetails(hint);
            if (!details) throw new Error("扑飞没有返回完整漫画详情或章节目录，请重试");
            this.saveData("details_v4", { key, at: Date.now(), data: details });
            return new ComicDetails(details);
        },
        loadEp: async (comicId, epId) => {
            const key = this.pageKey(epId);
            const cached = this.cachedPages().find(v => v && v.key === key && Date.now() - v.at < 10 * 60 * 1000 &&
                Array.isArray(v.images) && v.images.length && v.images.every(url => typeof url === "string" && /^https?:\/\//i.test(url)));
            if (cached) return { images: cached.images };
            const url = this.abs(epId);
            let images = url.startsWith("https://manwaxu.cc/api/comic/image/") ? await this.publicPages(url)
                : url.startsWith("https://v2.apikk.top/api/v2/chapter/getinfo?")
                    ? await this.supplierPages(url) : this.parseImages(await this.html(url));
            if (!images) {
                const alternate = this.alternatePage(url);
                if (alternate) images = this.parseImages(await this.html(alternate));
            }
            if (!images) throw new Error("扑飞章节没有返回有效图片列表，请重试");
            this.rememberPages(key, images);
            return { images };
        },
        onImageLoad: (url) => new ImageLoadingConfig({ url, headers: {
            "User-Agent": this.headers["User-Agent"],
            "Referer": /^https:\/\/tu\.mhttu\.cc\//.test(url) ? "https://manwaxu.cc/"
                : /^https:\/\/dmw\.546457\.xyz\//.test(url) ? "https://www.guoman.net/"
                : /\.6wm\.top\//.test(url) ? "https://manhuafree.com/" : this.baseUrl + "/"
        } })
    };
}
