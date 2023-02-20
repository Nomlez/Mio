package com.stormunblessed


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import org.jsoup.nodes.Element


class NineAnimeProvider : MainAPI() {
    override var mainUrl = "https://9anime.id"
    override var name = "9Anime"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)
    override val hasQuickSearch = true

    private val vrfInterceptor by lazy { JsVrfInterceptor(mainUrl) }

    // taken from https://github.com/saikou-app/saikou/blob/b35364c8c2a00364178a472fccf1ab72f09815b4/app/src/main/java/ani/saikou/parsers/anime/NineAnime.kt
    // GNU General Public License v3.0 https://github.com/saikou-app/saikou/blob/main/LICENSE.md
    companion object {
        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("(dub)", ignoreCase = true)) {
                DubStatus.Dubbed
            } else {
                DubStatus.Subbed
            }
        }


        fun encode(input: String): String =
            java.net.URLEncoder.encode(input, "utf-8").replace("+", "%2B")

        private fun decode(input: String): String = java.net.URLDecoder.decode(input, "utf-8")
    }

    override val mainPage = mainPageOf(
        "$mainUrl/ajax/home/widget/trending?page=" to "Trending",
        "$mainUrl/ajax/home/widget/updated-all?page=" to "All",
        "$mainUrl/ajax/home/widget/updated-sub?page=" to "Recently Updated (SUB)",
        "$mainUrl/ajax/home/widget/updated-dub?page=" to "Recently Updated (DUB)",
        "$mainUrl/ajax/home/widget/updated-china?page=" to "Recently Updated (Chinese)",
        "$mainUrl/ajax/home/widget/random?page=" to "Random",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        vrfInterceptor.wake()
        val home = Jsoup.parse(
            app.get(
                url
            ).parsed<Response>().html!!
        ).select("div.item").mapNotNull { element ->
            val title = element.selectFirst(".info > .name") ?: return@mapNotNull null
            val link = title.attr("href").replace(Regex("\\/ep.*\$"),"")
            val poster = element.selectFirst(".poster > a > img")?.attr("src")
            val meta = element.selectFirst(".poster > a > .meta > .inner > .left")
            val subbedEpisodes = meta?.selectFirst(".sub")?.text()?.toIntOrNull()
            val dubbedEpisodes = meta?.selectFirst(".dub")?.text()?.toIntOrNull()

            newAnimeSearchResponse(title.text() ?: return@mapNotNull null, link) {
                this.posterUrl = poster
                addDubStatus(
                    dubbedEpisodes != null,
                    subbedEpisodes != null,
                    dubbedEpisodes,
                    subbedEpisodes
                )
            }
        }

        return newHomePageResponse(request.name, home, true)
    }

    data class Response(
        @JsonProperty("result") val html: String?,
        @JsonProperty("llaa"   ) var llaa   : String? = null,
        @JsonProperty("epurl" ) var epurl : String? = null
    )

    data class QuickSearchResponse(
        //@JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: QuickSearchResult? = null,
        //@JsonProperty("message") val message: String? = null,
        //@JsonProperty("messages") val messages: ArrayList<String> = arrayListOf()
    )

    data class QuickSearchResult(
        @JsonProperty("html") val html: String? = null,
        //@JsonProperty("linkMore") val linkMore: String? = null
    )

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val vrf = vrfInterceptor.getVrf(query)
        val url =
            "$mainUrl/ajax/anime/search?keyword=$query&vrf=${encode(vrf)}"
        val response = app.get(url).parsedSafe<QuickSearchResponse>()
        val document = Jsoup.parse(response?.result?.html ?: return null)
        return document.select(".items > a").mapNotNull { element ->
            val link = fixUrl(element?.attr("href") ?: return@mapNotNull null)
            val title = element.selectFirst(".info > .name")?.text() ?: return@mapNotNull null
            newAnimeSearchResponse(title, link) {
                posterUrl = element.selectFirst(".poster > span > img")?.attr("src")
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val vrf = vrfInterceptor.getVrf(query)        //?language%5B%5D=${if (selectDub) "dubbed" else "subbed"}&
        val url =
            "$mainUrl/filter?keyword=${query}&vrf=${encode(vrf)}&page=1"
        return app.get(url).document.select("#list-items div.ani.poster.tip > a").mapNotNull {
            val link = fixUrl(it.attr("href") ?: return@mapNotNull null)
            val img = it.select("img")
            val title = img.attr("alt")
            newAnimeSearchResponse(title, link) {
                posterUrl = img.attr("src")
            }
        }
    }


    private fun Int.toBoolean() = this == 1

    data class EpsInfo (

        @JsonProperty("llaa"     ) var llaa     : String?  = null,
        @JsonProperty("epurl"    ) var epurl    : String?  = null,
        @JsonProperty("needDUB" ) var needDub : Boolean? = null,

        )

    private fun Element.toGetEpisode(url: String, needDub: Boolean):Episode{
        //val ids = this.attr("data-ids").split(",", limit = 2)
        val epNum = this.attr("data-num")
            .toIntOrNull() // might fuck up on 7.5 ect might use data-slug instead
        val epTitle = this.selectFirst("span.d-title")?.text()
        val epurl = "$url/ep-$epNum"
        val data = "{\"llaa\":\"null\",\"epurl\":\"$epurl\",\"needDUB\":$needDub}"
        return Episode(
            data,
            epTitle,
            episode = epNum
        )
    }
    override suspend fun load(url: String): LoadResponse {
        val validUrl = url.replace("https://9anime.to", mainUrl)
        val doc = app.get(validUrl).document

        val meta = doc.selectFirst("#w-info") ?: throw ErrorLoadingException("Could not find info")
        val ratingElement = meta.selectFirst(".brating > #w-rating")
        val id = ratingElement?.attr("data-id") ?: throw ErrorLoadingException("Could not find id")
        val binfo =
            meta.selectFirst(".binfo") ?: throw ErrorLoadingException("Could not find binfo")
        val info = binfo.selectFirst(".info") ?: throw ErrorLoadingException("Could not find info")
        val backimginfo = doc.selectFirst("#player")?.attr("style")
        val backimgRegx = Regex("(http|https).*jpg")
        val backposter = backimgRegx.find(backimginfo.toString())?.value ?: ""
        val title = (info.selectFirst(".title") ?: info.selectFirst(".d-title"))?.text()
            ?: throw ErrorLoadingException("Could not find title")
        val vrf = encode(vrfInterceptor.getVrf(id))

        val episodeListUrl = "$mainUrl/ajax/episode/list/$id?vrf=${vrf}"
        val body =
            app.get(episodeListUrl).parsedSafe<Response>()?.html
                ?: throw ErrorLoadingException("Could not parse json with Vrf=$vrf id=$id url=\n$episodeListUrl")

        val subEpisodes = ArrayList<Episode>()
        val dubEpisodes = ArrayList<Episode>()
        val genres = doc.select("div.meta:nth-child(1) > div:contains(Genre:) a").mapNotNull { it.text() }
        val recss = doc.select("div#watch-second .w-side-section div.body a.item").mapNotNull { rec ->
            val href = rec.attr("href")
            val rectitle = rec.selectFirst(".name")?.text() ?: ""
            val recimg = rec.selectFirst("img")?.attr("src")
            newAnimeSearchResponse(rectitle,fixUrl(href)){
                this.posterUrl = recimg
            }
        }
        val status = when (doc.selectFirst("div.meta:nth-child(1) > div:contains(Status:) span")?.text()) {
            "Releasing" -> ShowStatus.Ongoing
            "Completed" -> ShowStatus.Completed
            else -> null
        }

        val typetwo =  when(doc.selectFirst("div.meta:nth-child(1) > div:contains(Type:) span")?.text())  {
            "OVA" -> TvType.Anime
            "MOVIE" -> TvType.AnimeMovie
            else -> TvType.Anime
        }
        val duration = doc.selectFirst(".bmeta > div > div:contains(Duration:) > span")?.text()

        Jsoup.parse(body).body().select(".episodes > ul > li > a").apmap { element ->
            val dub = element.attr("data-dub").toInt().toBoolean()
            val sub = element.attr("data-sub").toInt().toBoolean()

            if (dub) {
                dubEpisodes.add(element.toGetEpisode(url, true))
            }
            if (sub) {
                subEpisodes.add(element.toGetEpisode(url, false))
            }

        }
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            addEpisodes(DubStatus.Dubbed, dubEpisodes)
            addEpisodes(DubStatus.Subbed, subEpisodes)
            plot = info.selectFirst(".synopsis > .shorting > .content")?.text()
            posterUrl = binfo.selectFirst(".poster > span > img")?.attr("src")
            rating = ratingElement.attr("data-score").toFloat().times(1000f).toInt()
            this.backgroundPosterUrl = backposter
            this.tags = genres
            this.recommendations = recss
            this.showStatus = status
            this.type = typetwo
            addDuration(duration)
        }
    }

    data class Result(
        @JsonProperty("url")
        val url: String? = null
    )

    data class Links(
        @JsonProperty("result")
        val result: Result? = null
    )

    /*private suspend fun getEpisodeLinks(id: String): Links? {
        return app.get("$mainUrl/ajax/server/$id?vrf=encodeVrf(id, cipherKey)}").parsedSafe()
    }*/

    private suspend fun getStream(
        streamLink: String,
        name: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    )  {
        return generateM3u8(
            this.name,
            streamLink,
            referer
        ).forEach { sub ->
            callback(
                ExtractorLink(
                    this.name,
                    name,
                    sub.url,
                    referer,
                    getQualityFromName(sub.quality.toString()),
                    true
                )
            )
        }
    }


    private suspend fun getM3U8(epurl: String, lang: String, callback: (ExtractorLink) -> Unit):Boolean{
        val isdub = lang == "dub"
        val vidstream = app.get(epurl, interceptor = JsInterceptor("41", lang), timeout = 45)
        //val mcloud = app.get(epurl, interceptor = JsInterceptor("28", lang), timeout = 45)
        val vidurl = vidstream.url
        //val murl = mcloud.url
        val ll = listOf(vidurl)
        ll.apmap {link ->
            val vv = link.contains("mcloud")
            val name1 = if (vv) "Mcloud" else "Vidstream"
            val ref = if (vv) "https://mcloud.to/" else ""
            val name2 = if (isdub) "$name1 Dubbed" else "$name1 Subbed"
            getStream(link, name2, ref ,callback)
        }
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsedata = parseJson<EpsInfo>(data)
        val epurl = parsedata.epurl
        val needDub = parsedata.needDub == true
        if (needDub) {
            getM3U8(epurl!!, "dub", callback)
        }
        if (!needDub) {
            getM3U8(epurl!!, "sub", callback)
        }
        return true
    }
}
