package desktop.api

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.Episode as CsEpisode

/** Cloudstream types → DTOs. */
object Mapper {
    fun searchItem(provider: String, r: SearchResponse) = SearchItem(
        provider = provider,
        name = r.name,
        url = r.url,
        type = r.type?.name,
        poster = r.posterUrl,
        posterHeaders = r.posterHeaders?.takeIf { it.isNotEmpty() },
        quality = r.quality?.name,
        year = when (r) {
            is MovieSearchResponse -> r.year
            is TvSeriesSearchResponse -> r.year
            is AnimeSearchResponse -> r.year
            else -> null
        },
        score = r.score?.toDouble(10),
    )

    fun isNsfw(r: SearchResponse) = r.type == TvType.NSFW

    fun homeRow(provider: String, list: HomePageList, showNsfw: Boolean) = HomeRow(
        name = list.name,
        horizontal = list.isHorizontalImages,
        items = list.list.filter { showNsfw || !isNsfw(it) }.map { searchItem(provider, it) },
    )

    private fun episode(e: CsEpisode, dub: String? = null) = Episode(
        data = e.data,
        name = e.name,
        season = e.season,
        episode = e.episode,
        poster = e.posterUrl,
        description = e.description,
        date = e.date,
        runtime = e.runTime,
        dub = dub,
    )

    /** Episodes in play order: by season, then episode number, keeping the provider's order for ties. */
    private fun ordered(list: List<CsEpisode>) =
        list.withIndex().sortedWith(compareBy({ it.value.season ?: 0 }, { it.value.episode ?: Int.MAX_VALUE }, { it.index })).map { it.value }

    fun details(provider: String, r: LoadResponse, providerNsfw: Boolean, showNsfw: Boolean): Details {
        val episodes: List<Episode>
        var kind = "other"
        var movieData: String? = null
        when (r) {
            is MovieLoadResponse -> { kind = "movie"; movieData = r.dataUrl; episodes = emptyList() }
            is LiveStreamLoadResponse -> { kind = "live"; movieData = r.dataUrl; episodes = emptyList() }
            is TorrentLoadResponse -> { kind = "torrent"; episodes = emptyList() }
            is TvSeriesLoadResponse -> { kind = "series"; episodes = ordered(r.episodes).map { episode(it) } }
            is AnimeLoadResponse -> {
                kind = "anime"
                episodes = r.episodes.entries.sortedBy { it.key.ordinal }
                    .flatMap { (dub, list) -> ordered(list).map { episode(it, dub.name) } }
            }
            else -> episodes = emptyList()
        }
        val seasonNames = (r as? TvSeriesLoadResponse)?.seasonNames ?: (r as? AnimeLoadResponse)?.seasonNames
        val seasons = seasonNames?.map { Season(it.season, it.name, it.displaySeason) }
            ?: episodes.mapNotNull { it.season }.distinct().sorted().map { Season(it, null, it) }
        return Details(
            provider = provider,
            url = r.url,
            name = r.name,
            type = r.type.name,
            kind = kind,
            poster = r.posterUrl,
            posterHeaders = r.posterHeaders?.takeIf { it.isNotEmpty() },
            background = r.backgroundPosterUrl,
            logo = r.logoUrl,
            plot = r.plot,
            year = r.year,
            tags = r.tags.orEmpty(),
            score = r.score?.toDouble(10),
            duration = r.duration,
            contentRating = r.contentRating,
            showStatus = (r as? TvSeriesLoadResponse)?.showStatus?.name ?: (r as? AnimeLoadResponse)?.showStatus?.name,
            actors = r.actors.orEmpty().map { Actor(it.actor.name, it.actor.image, it.roleString ?: it.role?.name) },
            trailers = r.trailers.map { it.extractorUrl },
            recommendations = r.recommendations.orEmpty().filter { showNsfw || !isNsfw(it) }.map { searchItem(provider, it) },
            seasons = seasons,
            episodes = episodes,
            movieData = movieData,
            nsfw = providerNsfw || r.type == TvType.NSFW,
            malId = runCatching { r.getMalId()?.toIntOrNull() }.getOrNull(),
            aniListId = runCatching { r.getAniListId()?.toIntOrNull() }.getOrNull(),
        )
    }

    fun qualityLabel(q: Int) = when (q) {
        Qualities.Unknown.value -> "Auto"
        Qualities.P2160.value -> "4K"
        else -> if (q > 0) "${q}p" else "?"
    }
}
