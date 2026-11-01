package desktop.db

import desktop.api.Bookmark
import desktop.api.ContinueEntry
import desktop.api.HistoryEntry
import desktop.api.Progress

/** History, progress, bookmarks and link stats on top of [Database]. */
class Store(private val db: Database) {
    private fun now() = System.currentTimeMillis()

    fun touchHistory(provider: String, url: String, title: String, poster: String?, type: String?, nsfw: Boolean) {
        db.update(
            """INSERT INTO history(provider, url, title, poster, type, nsfw, last_watched) VALUES(?,?,?,?,?,?,?)
               ON CONFLICT(provider, url) DO UPDATE SET title=excluded.title, poster=excluded.poster,
               type=excluded.type, nsfw=excluded.nsfw, last_watched=excluded.last_watched""",
            provider, url, title, poster, type, if (nsfw) 1 else 0, now(),
        )
    }

    fun history(showNsfw: Boolean, limit: Int = 200): List<HistoryEntry> =
        db.query(
            "SELECT provider, url, title, poster, type, last_watched FROM history WHERE (? OR nsfw = 0) ORDER BY last_watched DESC LIMIT ?",
            showNsfw, limit,
        ) { HistoryEntry(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getLong(6)) }

    fun deleteHistory(provider: String, url: String) {
        db.update("DELETE FROM history WHERE provider = ? AND url = ?", provider, url)
        db.update("DELETE FROM progress WHERE provider = ? AND url = ?", provider, url)
    }

    fun saveProgress(provider: String, url: String, episodeData: String, label: String?, position: Double, duration: Double, watched: Boolean) {
        db.update(
            """INSERT INTO progress(provider, episode_data, url, label, position, duration, watched, updated) VALUES(?,?,?,?,?,?,?,?)
               ON CONFLICT(provider, episode_data) DO UPDATE SET url=excluded.url, label=excluded.label, position=excluded.position,
               duration=excluded.duration, watched=MAX(progress.watched, excluded.watched), updated=excluded.updated""",
            provider, episodeData, url, label, position, duration, if (watched) 1 else 0, now(),
        )
    }

    fun setWatched(provider: String, url: String, episodeData: String, watched: Boolean) {
        db.update(
            """INSERT INTO progress(provider, episode_data, url, position, duration, watched, updated) VALUES(?,?,?,0,0,?,?)
               ON CONFLICT(provider, episode_data) DO UPDATE SET watched=excluded.watched, updated=excluded.updated""",
            provider, episodeData, url, if (watched) 1 else 0, now(),
        )
    }

    fun progress(provider: String, episodeData: String): Progress? =
        db.query("SELECT position, duration, watched FROM progress WHERE provider = ? AND episode_data = ?", provider, episodeData) {
            Progress(it.getDouble(1), it.getDouble(2), it.getInt(3) == 1)
        }.firstOrNull()

    fun progressFor(provider: String): Map<String, Progress> =
        db.query("SELECT episode_data, position, duration, watched FROM progress WHERE provider = ?", provider) {
            it.getString(1) to Progress(it.getDouble(2), it.getDouble(3), it.getInt(4) == 1)
        }.toMap()

    /** Most recent unfinished item per title, newest first. */
    fun continueWatching(showNsfw: Boolean, limit: Int = 30): List<ContinueEntry> =
        db.query(
            """SELECT p.provider, p.url, h.title, h.poster, p.episode_data, p.label, p.position, p.duration, p.updated
               FROM progress p JOIN history h ON h.provider = p.provider AND h.url = p.url
               WHERE p.watched = 0 AND p.position > 30 AND (? OR h.nsfw = 0)
               AND p.updated = (SELECT MAX(updated) FROM progress q WHERE q.provider = p.provider AND q.url = p.url)
               ORDER BY p.updated DESC LIMIT ?""",
            showNsfw, limit,
        ) {
            ContinueEntry(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5),
                it.getString(6), it.getDouble(7), it.getDouble(8), it.getLong(9))
        }

    fun bookmarks(showNsfw: Boolean): List<Bookmark> =
        db.query("SELECT provider, url, title, poster, type, list, added FROM bookmarks WHERE (? OR nsfw = 0) ORDER BY added DESC", showNsfw) {
            Bookmark(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getLong(7))
        }

    fun bookmarkList(provider: String, url: String): String? =
        db.query("SELECT list FROM bookmarks WHERE provider = ? AND url = ?", provider, url) { it.getString(1) }.firstOrNull()

    fun setBookmark(provider: String, url: String, title: String, poster: String?, type: String?, nsfw: Boolean, list: String?) {
        if (list == null) {
            db.update("DELETE FROM bookmarks WHERE provider = ? AND url = ?", provider, url)
            return
        }
        db.update(
            """INSERT INTO bookmarks(provider, url, title, poster, type, nsfw, list, added) VALUES(?,?,?,?,?,?,?,?)
               ON CONFLICT(provider, url) DO UPDATE SET list=excluded.list, title=excluded.title, poster=excluded.poster""",
            provider, url, title, poster, type, if (nsfw) 1 else 0, list, now(),
        )
    }

    fun recordLink(provider: String, source: String, success: Boolean) {
        val col = if (success) "successes" else "failures"
        db.update(
            "INSERT INTO link_stats(provider, source_name, $col) VALUES(?,?,1) ON CONFLICT(provider, source_name) DO UPDATE SET $col = $col + 1",
            provider, source,
        )
    }

    /** Success ratio per source for [provider], smoothed so a new source starts at 0.5. */
    fun linkScores(provider: String): Map<String, Double> =
        db.query("SELECT source_name, successes, failures FROM link_stats WHERE provider = ?", provider) {
            it.getString(1) to (it.getInt(2) + 1.0) / (it.getInt(2) + it.getInt(3) + 2.0)
        }.toMap()
}
