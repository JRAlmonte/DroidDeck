package com.droiddeck.launcher.core

import org.json.JSONArray

object GithubReleases {
    class Release(val tag: String, val published: String, val asset: String, val url: String, val sha256: String?)

    fun list(repo: String): JSONArray? =
        Downloader.downloadString("https://api.github.com/repos/$repo/releases?per_page=15")?.let { runCatching { JSONArray(it) }.getOrNull() }

    fun latest(releases: JSONArray, pick: (List<String>) -> String?): Release? {
        val candidates = (0 until releases.length()).mapNotNull { i ->
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft")) return@mapNotNull null
            val assets = r.optJSONArray("assets") ?: return@mapNotNull null
            val byName = (0 until assets.length()).associate { j -> assets.getJSONObject(j).let { it.getString("name") to it } }
            pick(byName.keys.toList())?.let { asset ->
                r.optBoolean("prerelease") to byName.getValue(asset).let { a ->
                    Release(r.optString("tag_name", asset), r.optString("published_at"), asset, a.getString("browser_download_url"),
                        Hashes.githubSha256(a.optString("digest")))
                }
            }
        }
        return (candidates.firstOrNull { !it.first } ?: candidates.firstOrNull())?.second
    }
}
