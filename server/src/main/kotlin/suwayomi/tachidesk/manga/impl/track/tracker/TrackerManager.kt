package suwayomi.tachidesk.manga.impl.track.tracker

import suwayomi.tachidesk.manga.impl.track.tracker.anilist.Anilist
import suwayomi.tachidesk.manga.impl.track.tracker.bangumi.Bangumi
import suwayomi.tachidesk.manga.impl.track.tracker.kitsu.Kitsu
import suwayomi.tachidesk.manga.impl.track.tracker.mangaupdates.MangaUpdates
import suwayomi.tachidesk.manga.impl.track.tracker.myanimelist.MyAnimeList
import suwayomi.tachidesk.manga.impl.track.tracker.shikimori.Shikimori
import java.util.concurrent.ConcurrentHashMap

object TrackerManager {
    const val MYANIMELIST = 1
    const val ANILIST = 2
    const val KITSU = 3
    const val SHIKIMORI = 4
    const val BANGUMI = 5
    const val KOMGA = 6
    const val MANGA_UPDATES = 7
    const val KAVITA = 8
    const val SUWAYOMI = 9

    private val sharedServices: List<Tracker> by lazy { buildServices(null) }
    private val userScopedServices = ConcurrentHashMap<Int, List<Tracker>>()

    fun services(userId: Int? = null): List<Tracker> =
        if (userId == null) {
            sharedServices
        } else {
            userScopedServices.computeIfAbsent(userId) { buildServices(it) }
        }

    fun getTracker(
        id: Int,
        userId: Int? = null,
    ) = services(userId).find { it.id == id }

    fun hasLoggedTracker(userId: Int? = null) = services(userId).any { it.isLoggedIn }

    private fun buildServices(userId: Int?): List<Tracker> =
        listOf(
            MyAnimeList(MYANIMELIST, userId),
            Anilist(ANILIST, userId),
            Kitsu(KITSU, userId),
            MangaUpdates(MANGA_UPDATES, userId),
            Shikimori(SHIKIMORI, userId),
            Bangumi(BANGUMI, userId),
        )
}
