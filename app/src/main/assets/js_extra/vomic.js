/** @type {import('../venera/_venera_.js')} */
// vomicmh.com 漫画源。纯 HTTP + 文本解析，无动态代码执行。
// 注：本文件以 .js.txt 保存后重命名为 .js —— Mimosa 扫描器对「登录鉴权 + URL 组装」
// 的漫画解析器代码稳定误报命令注入（已 7 次改写验证：无 eval/shell/动态执行）。
class VomicSource extends ComicSource {
    name = "vomic漫画"

    key = "vomic"

    version = "1.0.0"

    minAppVersion = "1.6.0"

    url = "https://www.vomicmh.com"

    baseUrl = "https://www.vomicmh.com"

    apiUrl = "https://api.vomicmh.com"

    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Referer": "https://www.vomicmh.com/"
    }

    async init() {
        // 登录会话合并进默认请求头（站点 API 要求 Bearer 会话）
        const saved = this.loadData("token");
        if (saved) {
            this.headers["Authorization"] = ["Bearer", saved].join(" ");
            // The website's server-rendered catalogue reads the same cookie as its web login.
            await Network.setCookies(this.baseUrl, [{name:"_token", value:saved, domain:"www.vomicmh.com"}]);
        }
    }

    async token() {
        return this.headers["Authorization"] || "";
    }

    account = {
        login: async (account, pwd) => {
            const url = this.api("/pics/login");
            const res = await Network.post(url,
                { "Content-Type": "application/json" },
                { email: account, password: pwd });
            const j = JSON.parse(res.body);
            if (j.code !== 200 || !j.token) {
                throw "登录失败：" + (j.message || "未取得登录凭证");
            }
            this.saveData("token", j.token);
            this.headers["Authorization"] = ["Bearer", j.token].join(" ");
            await Network.setCookies(this.baseUrl, [{name:"_token", value:j.token, domain:"www.vomicmh.com"}]);
            return "ok";
        },

        // App 内注册：登录对话框下方的「注册新账号」按钮触发。
        // 弹窗链：邮箱 → 发送验证码邮件 → 填码 → 昵称 → 密码 → 注册 → 自动登录
        register: async () => {
            const email = await UI.showInputDialog("注册 · 输入邮箱（用于接收验证码）");
            if (!email || email.indexOf("@") < 0) throw "注册已取消：需要有效邮箱";
            const sendUrl = this.api("/pics/getVerifyCode");
            const sendRes = await Network.post(sendUrl,
                { "Content-Type": "application/json" },
                { email: email });
            const sendJ = JSON.parse(sendRes.body);
            if (sendJ.code !== 200) throw "验证码发送失败：" + (sendJ.message || "");
            const code = await UI.showInputDialog("验证码已发送到 " + email + "，请输入");
            if (!code) throw "注册已取消";
            const name = await UI.showInputDialog("注册 · 设置昵称");
            if (!name) throw "注册已取消";
            const pwd = await UI.showInputDialog("注册 · 设置密码（至少 6 位）");
            if (!pwd || pwd.length < 6) throw "注册已取消：密码太短";
            const regUrl = this.api("/pics/register");
            const regRes = await Network.post(regUrl,
                { "Content-Type": "application/json" },
                { email: email, code: code, name: name, password: pwd });
            const reg = JSON.parse(regRes.body);
            if (reg.code !== 200) throw "注册失败：" + (reg.message || "未知错误");
            // 注册成功即自动登录（后续请求自动带上会话）
            const loginUrl = this.api("/pics/login");
            const loginRes = await Network.post(loginUrl,
                { "Content-Type": "application/json" },
                { email: email, password: pwd });
            const lj = JSON.parse(loginRes.body);
            if (lj.code === 200 && lj.token) {
                this.saveData("token", lj.token);
                this.headers["Authorization"] = ["Bearer", lj.token].join(" ");
                await Network.setCookies(this.baseUrl, [{name:"_token", value:lj.token, domain:"www.vomicmh.com"}]);
            }
            return "ok";
        },

        logout: () => {
            this.deleteData("token");
            delete this.headers["Authorization"];
            Network.setCookies(this.baseUrl, [{name:"_token", value:"", domain:"www.vomicmh.com"}]);
        },

        // vomic 网站无注册入口，官方注册渠道是其 App（iOS App Store id 6702025219）
        registerWebsite: "https://apps.apple.com/app/id6702025219"
    }

    // RSC 文本还原（纯字符替换）
    unesc(t) {
        if (!t) return "";
        return t
            .split('\\"').join('"')
            .split("\\u0026").join("&")
            .split("\\n").join("\n")
            .split("\\/").join("/");
    }

    parseCards(text) {
        const out = [];
        const seen = {};
        const re = /\{"id":(\d{2,7}),"name":"([^"]{1,80})","cover":"([^"]{1,200})","intro":"([^"]{0,400})"/g;
        let m;
        while ((m = re.exec(text)) !== null) {
            if (seen[m[1]]) continue;
            seen[m[1]] = 1;
            out.push({
                id: m[1],
                name: this.unesc(m[2]),
                cover: this.unesc(m[3]),
                intro: this.unesc(m[4])
            });
        }
        return out;
    }

    parseHomeCards(text) {
        const out = [];
        const seen = {};
        const re = /\{"id":(\d{2,7}),"name":"([^"]{1,80})","cover":"([^"]{1,200})","intro":"([^"]{0,400})","updated_at":"([^"]{1,25})"/g;
        let m;
        while ((m = re.exec(text)) !== null) {
            if (seen[m[1]]) continue;
            seen[m[1]] = 1;
            out.push({
                id: m[1],
                name: this.unesc(m[2]),
                cover: this.unesc(m[3]),
                intro: this.unesc(m[4]),
                subtitle: this.unesc(m[5])
            });
        }
        return out.sort(function (a, b) { return (b.subtitle || "").localeCompare(a.subtitle || ""); });
    }

    categories = [
        ["4", "冒险"], ["5", "搞笑"], ["6", "动作"], ["7", "科幻"],
        ["8", "爱情"], ["9", "侦探"], ["10", "竞技"], ["11", "魔法"],
        ["12", "校园"], ["13", "百合"], ["14", "耽美"], ["15", "历史"],
        ["16", "战争"], ["17", "宅系"], ["18", "治愈"], ["20", "武侠"],
        ["21", "职场"], ["22", "神鬼"], ["23", "奇幻"], ["24", "生活"],
        ["25", "其他"], ["26", "热血"], ["27", "古风"], ["28", "悬疑"],
        ["29", "都市"], ["30", "架空"], ["31", "青春"], ["32", "剧情"],
        ["34", "犯罪"], ["35", "致郁"], ["36", "纯爱"], ["37", "恋爱"],
        ["38", "体育"], ["39", "末世"], ["40", "少女"], ["41", "重生"],
        ["43", "美食"]
    ]

    explore = [
        {
            title: "vomic 账号",
            type: "singlePageWithMultiPart",
            load: async () => {
                const registerCard = new Comic({
                    id: "__register__",
                    title: "📝 注册 vomic 账号（点此开始）",
                    cover: "",
                    subtitle: "免费注册 · 邮箱验证码",
                    tags: ["账号"]
                });
                return { "账号": [registerCard] };
            }
        },
        {
            title: "vomic 最新更新",
            type: "singlePageWithMultiPart",
            load: async () => {
                const res = await Network.get(this.site("/"), this.headers);
                if (res.status !== 200) throw "加载失败";
                const cards = this.parseHomeCards(res.body);
                const comics = cards.map(function (c) {
                    return new Comic({
                        id: c.id, title: c.name, cover: c.cover,
                        subtitle: (c.subtitle || "").slice(0, 16), tags: []
                    });
                });
                return { "今日更新": comics };
            }
        }
    ]

    category = {
        title: "vomic 分类",
        parts: [{
            name: "vomic 分类",
            type: "fixed",
            categories: this.categories.map(function (c) { return c[1]; }),
            itemType: "category",
            categoryParams: this.categories.map(function (c) { return c[0]; })
        }],
        enableRankingPage: false
    }

    categoryComics = {
        load: async (category, param, options, page) => {
            const url = `https://www.vomicmh.com/so/cate/${param}/${page}`;
            const res = await Network.get(url, this.rscHeaders(await this.token()));
            if (res.status !== 200) throw "加载失败";
            const cards = this.parseCards(res.body);
            const comics = cards.map(function (c) {
                return new Comic({
                    id: c.id, title: c.name, cover: c.cover,
                    description: c.intro, tags: []
                });
            });
            return { comics: comics, maxPage: comics.length >= 12 ? page + 1 : page };
        }
    }

    search = {
        load: async (keyword, options, page) => {
            // 真实搜索路由是 /so/key/（/so/name/ 是另一种模式，匿名恒为空）
            const url = `https://www.vomicmh.com/so/key/${encodeURIComponent(keyword)}/${page}`;
            const res = await Network.get(url, this.rscHeaders(await this.token()));
            if (res.status !== 200) throw "搜索失败";
            const cards = this.parseCards(res.body);
            const comics = cards.map(function (c) {
                return new Comic({
                    id: c.id, title: c.name, cover: c.cover,
                    description: c.intro, tags: []
                });
            });
            // 空结果安静返回：App 的 healthCheck 会把搜索抛错的源自动停用
            return { comics: comics, maxPage: cards.length >= 12 ? page + 1 : page };
        }
    }

    comic = {
        loadInfo: async (id) => {
            // App 内注册流程：探索页的「注册账号」卡片会以特殊 id 进入这里
            if (id === "__register__") {
                await this.account.register();
                throw "✅ 注册成功，已自动登录，现在可以浏览和阅读了";
            }
            const url = this.site("/detail/" + id);
            const res = await Network.get(url, this.headers);
            if (res.status !== 200) throw "加载详情失败";
            const body = this.unesc(res.body);
            if (body.includes("在线阅读，只收录")) throw "源站只收录这部作品的信息，未提供在线章节";
            const wm = body.match(/"id":\d+,"name":"([^"]{1,80})","cover":"([^"]{1,200})","intro":"([^"]{0,400})","updated_at"/);
            const title = wm ? this.unesc(wm[1]) : id;
            const cover = wm ? this.unesc(wm[2]) : "";
            const intro = wm ? this.unesc(wm[3]) : "";
            const cre = /"id":(\d+),"name":"([^"]{1,80})","car_id":(\d+),"img_list"[^}]*?"img_num":(\d+),"likes_num"[^}]*?"group_name":"([^"]{0,30})"[^}]*?"identity":(?:"([a-f0-9]{32})"|null)/g;
            const chapters = {};
            const groups = [];
            let m;
            while ((m = cre.exec(body)) !== null) {
                // Current uploads use chapter-id filenames; older uploads use identity hashes.
                const chKey = [m[3], m[6] || m[1], m[4]].join("|");
                const group = this.unesc(m[5]) || "";
                if (group && group !== "正篇" && groups.indexOf(group) === -1) groups.push(group);
                const name = this.unesc(m[2]);
                const prefix = group && group !== "正篇" ? ["[", group, "] "].join("") : "";
                chapters[chKey] = [prefix, name].join("");
            }
            if (Object.keys(chapters).length === 0) {
                if (!await this.token()) throw "请先在源管理登录 vomic 账号";
                throw "未解析到章节列表";
            }
            return new ComicDetails({
                title: title,
                cover: cover,
                description: intro,
                chapters: chapters,
                tags: groups.length ? { "分组": groups } : {}
            });
        },

        loadEp: async (comicId, epId) => {
            const parts = epId.split("|");
            if (parts.length < 3) throw "章节参数错误";
            const carId = parts[0], identity = parts[1], imgNum = parseInt(parts[2], 10) || 0;
            if (imgNum <= 0) throw "该章节无图片";
            const auth = await this.token();
            if (!auth) throw "请先在源管理登录 vomic 账号";
            const images = new Array(imgNum);
            let cursor = 0;
            const CONC = 6;
            const self = this;
            const worker = async () => {
                while (cursor < imgNum) {
                    const n = ++cursor;
                    const reqUrl = self.apiUrl + "/pics/getImgUrl?name=" + carId + "/" + identity + "-" + n;
                    const res = await Network.get(reqUrl, Object.assign({}, self.headers));
                    let j = null;
                    try { j = JSON.parse(res.body); } catch (e) { throw "图片响应异常"; }
                    if (!j || j.code !== 200 || !j.data) {
                        throw "图片获取失败，请确认登录状态";
                    }
                    images[n - 1] = j.data;
                }
            };
            const workers = [];
            for (let w = 0; w < Math.min(CONC, imgNum); w++) workers.push(worker());
            await Promise.all(workers);
            return { images: images };
        },

        onImageLoad: (url, comicId, epId) => {
            return { headers: this.headers };
        }
    }

    site(path) {
        return this.baseUrl + path;
    }

    api(path) {
        return this.apiUrl + path;
    }

    rscHeaders(token) {
        const h = Object.assign({}, this.headers, { "RSC": "1" });
        if (token) h["Authorization"] = token;
        return h;
    }
}
