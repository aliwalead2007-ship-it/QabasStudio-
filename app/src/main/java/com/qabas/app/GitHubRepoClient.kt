package com.qabas.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class RepoFile(
    val name: String,
    val path: String,
    val type: String,
    val sha: String,
    val size: Long = 0,
    val html_url: String = "",
    val download_url: String = "",
    val branch: String = "main",
) {
    val isDirectory: Boolean get() = type == "tree"
    val isFile: Boolean get() = type == "blob"
}

data class RepoCommitInfo(
    val sha: String,
    val message: String,
    val authorName: String,
    val date: String,
)

data class RepoBranchInfo(
    val name: String,
    val sha: String,
)

data class RepoContentResponse(
    val name: String,
    val path: String,
    val sha: String,
    val content: String,
    val encoding: String,
)

class GitHubRepoClient(
    private val owner: String,
    private val repo: String,
    private val token: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun authHeader(): String = if (token.isNotBlank()) "Bearer $token" else ""

    private suspend fun <T> get(path: String, deserialize: (String) -> T): T? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url("https://api.github.com$path")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Qabas-Studio")
            if (authHeader().isNotBlank()) builder.header("Authorization", authHeader())
            val response = client.newCall(builder.build()).execute()
            if (!response.isSuccessful) return@withContext null
            deserialize(response.body?.string() ?: return@withContext null)
        } catch (e: Exception) { null }
    }

    private suspend fun post(path: String, body: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com$path")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Qabas-Studio")
                .header("Authorization", authHeader())
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.code == 201 || it.code == 200 }
        } catch (e: Exception) { false }
    }

    private suspend fun put(path: String, body: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com$path")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Qabas-Studio")
                .header("Authorization", authHeader())
                .put(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.code == 200 || it.code == 201 }
        } catch (e: Exception) { false }
    }

    private suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com$path")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Qabas-Studio")
                .header("Authorization", authHeader())
                .delete()
                .build()
            client.newCall(req).execute().use { it.code == 200 || it.code == 204 }
        } catch (e: Exception) { false }
    }

    private fun encodePath(p: String): String = URLEncoder.encode(p.trim(), "UTF-8").replace("+", "%20")

    suspend fun getTree(branch: String = "main", path: String = "", recursive: Boolean = true): List<RepoFile>? {
        val p = if (path.isBlank()) "" else "/$path"
        val url = "/repos/$owner/$repo/git/trees/$branch?recursive=${if (recursive) 1 else 0}$p"
        return get(url) { raw ->
            val obj = JSONObject(raw)
            val treeArr = obj.optJSONArray("tree") ?: return@get emptyList()
            val result = mutableListOf<RepoFile>()
            for (i in 0 until treeArr.length()) {
                val e = treeArr.getJSONObject(i)
                result.add(
                    RepoFile(
                        name = e.optString("name"),
                        path = e.optString("path"),
                        type = e.optString("type"),
                        sha = e.optString("sha"),
                        size = e.optLong("size", 0),
                        html_url = e.optString("html_url"),
                        download_url = e.optString("download_url", ""),
                        branch = branch,
                    )
                )
            }
            result
        }
    }

    suspend fun getCommits(branch: String = "main", perPage: Int = 30): List<RepoCommitInfo>? {
        val path = "/repos/$owner/$repo/commits?sha=${encodePath(branch)}&per_page=$perPage"
        return get(path) { raw ->
            val arr = JSONArray(raw)
            val result = mutableListOf<RepoCommitInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sha = o.optString("sha", "")
                val c = o.optJSONObject("commit") ?: continue
                val a = c.optJSONObject("author")
                result.add(
                    RepoCommitInfo(
                        sha = sha,
                        message = c.optString("message", "").takeLines(1),
                        authorName = a?.optString("name") ?: "Unknown",
                        date = a?.optString("date")?.take(10) ?: "",
                    )
                )
            }
            result
        }
    }

    suspend fun getBranches(): List<RepoBranchInfo>? {
        val path = "/repos/$owner/$repo/branches?per_page=50"
        return get(path) { raw ->
            val arr = JSONArray(raw)
            val result = mutableListOf<RepoBranchInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val c = o.optJSONObject("commit")
                result.add(
                    RepoBranchInfo(
                        name = o.optString("name"),
                        sha = c?.optString("sha") ?: "",
                    )
                )
            }
            result
        }
    }

    suspend fun createBranch(branchName: String, fromSha: String): Boolean {
        val body = JSONObject()
            .put("ref", "refs/heads/$branchName")
            .put("sha", fromSha)
            .toString()
        return post("/repos/$owner/$repo/git/refs", body)
    }

    suspend fun getFileContent(path: String, branch: String = "main"): RepoContentResponse? {
        val url = "/repos/$owner/$repo/contents/${encodePath(path)}?ref=$branch"
        return get(url) { raw ->
            val obj = JSONObject(raw)
            RepoContentResponse(
                name = obj.optString("name"),
                path = obj.optString("path"),
                sha = obj.optString("sha"),
                content = obj.optString("content", ""),
                encoding = obj.optString("encoding", "base64"),
            )
        }
    }

    suspend fun createFile(path: String, branch: String, message: String, content: String, sha: String? = null): Boolean {
        val obj = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .put("branch", branch)
        if (sha != null) obj.put("sha", sha)
        return put("/repos/$owner/$repo/contents/${encodePath(path)}", obj.toString())
    }

    suspend fun deleteFile(path: String, branch: String, message: String, sha: String): Boolean {
        val enc = URLEncoder.encode(message, "UTF-8")
        val url = "/repos/$owner/$repo/contents/${encodePath(path)}?message=$enc&sha=${URLEncoder.encode(sha, "UTF-8")}&branch=${URLEncoder.encode(branch, "UTF-8")}"
        return delete(url)
    }

    suspend fun getBranchSha(branch: String = "main"): String? {
        val path = "/repos/$owner/$repo/git/refs/heads/${encodePath(branch)}"
        return get(path) { raw ->
            val obj = JSONObject(raw)
            val o = obj.optJSONObject("object")
            o?.optString("sha")
        }
    }
}

private fun String.takeLines(n: Int): String {
    val parts = this.lines()
    return parts.take(n).joinToString("\n")
}
