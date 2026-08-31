// Manga Demon — Keiyoushi → VeneraNext
// Versão PT-BR 1.0.8 — compatível com a API de fontes JavaScript do VeneraNext.
// Baseada na implementação MangaDemon.kt do Keiyoushi.
// Source: https://github.com/keiyoushi/extensions-source

class MangaDemon extends ComicSource {
    name = "Manga Demon — Português (BR)"
    key = "mangademon"
    version = "1.0.8"
    minAppVersion = "1.0.0"
    url = "https://demonicscans.org"

    get baseUrl() {
        return "https://demonicscans.org"
    }

    headers() {
        return {
            "Referer": this.baseUrl + "/"
        }
    }

    async request(path) {
        const url = this.absoluteUrl(path)
        if (!url) throw "URL inválida"

        const res = await Network.get(url, this.headers())

        if (!res || res.status >= 400) {
            throw "Erro HTTP " + (res ? res.status : "desconhecido")
        }

        return new HtmlDocument(res.body || "")
    }

    // Evita depender de URL()/URL constructor, mantendo compatibilidade
    // com o runtime JavaScript do VeneraNext.
    absoluteUrl(value) {
        if (!value) return null

        const url = String(value).trim()
        if (!url) return null

        if (url.startsWith("http://") || url.startsWith("https://"))
            return url

        if (url.startsWith("//"))
            return "https:" + url

        if (url.startsWith("/"))
            return this.baseUrl + url

        return this.baseUrl + "/" + url
    }

    parsePopularComic(e) {
        const a = e.querySelector("a")
        const title = e.querySelector("h1")
        const img = e.querySelector("img")

        if (!a || !title) return null

        const id = this.absoluteUrl(a.attributes["href"])
        if (!id) return null

        return {
            id: id,
            title: title.text.trim(),
            cover: img ? this.absoluteUrl(img.attributes["src"]) : null
        }
    }

    parseLatestComic(e) {
        if (e.querySelector(".toffee-badge")) return null

        const info = e.querySelector("div.updates-element-info")
        const a = info ? info.querySelector("a") : null
        const img = e.querySelector("div.thumb img")

        if (!a) return null

        const id = this.absoluteUrl(a.attributes["href"])
        if (!id) return null

        return {
            id: id,
            title: a.text.trim(),
            cover: img ? this.absoluteUrl(img.attributes["src"]) : null
        }
    }

    parseSearchComic(e) {
        const title = e.querySelector("div.seach-right > div")
        const img = e.querySelector("img")
        const href = e.attributes["href"]

        if (!href || !title) return null

        const id = this.absoluteUrl(href)
        if (!id) return null

        return {
            id: id,
            title: title.text.trim(),
            cover: img ? this.absoluteUrl(img.attributes["src"]) : null
        }
    }

    hasNext(document) {
        for (const a of document.querySelectorAll("div.pagination a")) {
            const text = a.text.trim().toLowerCase()
            if (
                text.includes("next") ||
                text.includes("próxima") ||
                text.includes("proxima")
            ) {
                return true
            }
        }
        return false
    }

    parseList(document, type, page) {
        const selector = type === "latest"
            ? "div#updates-container > div.updates-element"
            : "div#advanced-content > div.advanced-element"

        const parser = type === "latest"
            ? e => this.parseLatestComic(e)
            : e => this.parsePopularComic(e)

        const comics = document.querySelectorAll(selector)
            .map(parser)
            .filter(Boolean)

        return {
            comics: comics,
            maxPage: this.hasNext(document) ? page + 1 : page
        }
    }

    explore = [
        {
            title: "Mais populares",
            type: "multiPageComicList",
            load: async page => {
                const d = await this.request(
                    `/advanced.php?list=${page}&status=all&orderby=VIEWS%20DESC`
                )
                return this.parseList(d, "popular", page)
            }
        },
        {
            title: "Últimas atualizações",
            type: "multiPageComicList",
            load: async page => {
                const d = await this.request(`/lastupdates.php?list=${page}`)
                return this.parseList(d, "latest", page)
            }
        }
    ]

    category = null
    categoryComics = null

    search = {
        load: async (keyword, options, page) => {
            const d = await this.request(
                `/search.php?manga=${encodeURIComponent(keyword || "")}`
            )

            return {
                comics: d.querySelectorAll("body > a[href]")
                    .map(e => this.parseSearchComic(e))
                    .filter(Boolean),
                maxPage: 1
            }
        },

        optionList: []
    }

    comic = {
        loadInfo: async id => {
            const d = await this.request(id)

            const info = d.querySelector("div#manga-info-container")
            if (!info) throw "Detalhes do mangá não encontrados"

            const title = info.querySelector("h1.big-fat-titles")
            const cover = info.querySelector("div#manga-page img")
            const desc = info.querySelector(
                "div#manga-info-rightColumn > div > div.white-font"
            )

            const genres = info.querySelectorAll("div.genres-list > li")
                .map(e => e.text.trim())
                .filter(Boolean)

            let author = null
            let statusText = null

            // Não usa :scope, mantendo compatibilidade com o parser HTML.
            for (const div of info.querySelectorAll("div#manga-info-stats > div")) {
                const items = div.querySelectorAll("li")
                if (items.length < 2) continue

                const label = items[0].text.trim().toLowerCase()
                const value = items[1].text.trim()

                if (label.includes("author")) author = value
                if (label.includes("status")) statusText = value
            }

            if (author && author.toLowerCase().includes("updating"))
                author = null

            let status = "Desconhecido"
            const normalizedStatus = statusText
                ? statusText.toLowerCase()
                : ""

            if (normalizedStatus.includes("ongoing")) {
                status = "Em andamento"
            } else if (normalizedStatus.includes("completed")) {
                status = "Concluído"
            } else if (normalizedStatus.includes("hiatus")) {
                status = "Em hiato"
            } else if (normalizedStatus.includes("cancelled")) {
                status = "Cancelado"
            }

            // VeneraNext espera chapters como mapa:
            // { "chapterId": "chapterTitle" }.
            const chapters = {}

            for (const e of d.querySelectorAll(
                "div#chapters-list a.chplinks"
            )) {
                const href = e.attributes["href"]
                if (!href) continue

                const chapterId = this.absoluteUrl(href)
                if (!chapterId) continue

                const chapterTitle = e.text.trim()
                if (!chapterTitle) continue

                chapters[chapterId] = chapterTitle
            }

            return {
                id: id,
                title: title ? title.text.trim() : "",
                cover: cover ? this.absoluteUrl(cover.attributes["src"]) : null,
                subtitle: author || "",
                description: desc ? desc.text.trim() : "",
                tags: {
                    "Gênero": genres
                },
                status: status,
                chapters: chapters,
                url: id
            }
        },

        loadEp: async (comicId, epId) => {
            const d = await this.request(epId)

            const images = d.querySelectorAll("div > img.imgholder")
                .map(e => e.attributes["src"])
                .filter(Boolean)
                .map(src => this.absoluteUrl(src))
                .filter(Boolean)

            if (images.length === 0)
                throw "Nenhuma imagem encontrada no capítulo"

            return {
                images: images
            }
        },

        onImageLoad: (url, comicId, epId) => ({
            headers: {
                "Referer": this.baseUrl + "/"
            }
        }),

        idMatch: null,

        link: {
            domains: ["demonicscans.org"],
            linkToId: url => {
                if (!url) return null

                const value = String(url).trim()
                if (!value) return null

                if (
                    value.startsWith("http://") ||
                    value.startsWith("https://")
                ) {
                    return value
                }

                if (value.startsWith("//"))
                    return "https:" + value

                if (value.startsWith("/"))
                    return "https://demonicscans.org" + value

                return "https://demonicscans.org/" + value
            }
        },

        enableTagsTranslate: false
    }
}
