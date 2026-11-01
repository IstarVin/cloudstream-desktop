package desktop.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** SQLite in the data dir. One connection, serialized: the load is a handful of writes every few seconds. */
class Database(file: File) {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    init {
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute(
                """CREATE TABLE IF NOT EXISTS history(
                    provider TEXT NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL, poster TEXT, type TEXT,
                    nsfw INTEGER NOT NULL DEFAULT 0, last_watched INTEGER NOT NULL,
                    PRIMARY KEY(provider, url))"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS progress(
                    provider TEXT NOT NULL, episode_data TEXT NOT NULL, url TEXT NOT NULL, label TEXT,
                    position REAL NOT NULL, duration REAL NOT NULL, watched INTEGER NOT NULL DEFAULT 0,
                    updated INTEGER NOT NULL,
                    PRIMARY KEY(provider, episode_data))"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS bookmarks(
                    provider TEXT NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL, poster TEXT, type TEXT,
                    nsfw INTEGER NOT NULL DEFAULT 0, list TEXT NOT NULL, added INTEGER NOT NULL,
                    PRIMARY KEY(provider, url))"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS link_stats(
                    provider TEXT NOT NULL, source_name TEXT NOT NULL,
                    successes INTEGER NOT NULL DEFAULT 0, failures INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(provider, source_name))"""
            )
            st.execute("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
    }

    @Synchronized
    fun update(sql: String, vararg args: Any?): Int = conn.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeUpdate()
    }

    @Synchronized
    fun <T> query(sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> = conn.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeQuery().use { rs ->
            buildList { while (rs.next()) add(row(rs)) }
        }
    }
}
