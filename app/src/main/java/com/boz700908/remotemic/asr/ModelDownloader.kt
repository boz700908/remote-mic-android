package com.boz700908.remotemic.asr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 逐文件下载模型到 filesDir/models/<id>/。
 * 用 HttpURLConnection 而不是额外引依赖；支持断点续传与取消。
 */
class ModelDownloader(private val modelsRoot: File) {

    fun modelDir(spec: AsrModelSpec): File = File(modelsRoot, spec.id)

    fun isReady(spec: AsrModelSpec): Boolean =
        spec.files.all { File(modelDir(spec), it.relativePath).let { f -> f.isFile && f.length() > 0 } }

    fun downloadedBytes(spec: AsrModelSpec): Long =
        spec.files.sumOf { f -> File(modelDir(spec), f.relativePath).let { if (it.isFile) it.length() else 0L } }

    /**
     * @param onProgress (已完成字节, 预计总字节, 当前文件名)
     */
    suspend fun download(
        spec: AsrModelSpec,
        onProgress: (done: Long, total: Long, current: String) -> Unit = { _, _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val root = modelDir(spec)
        root.mkdirs()
        val totalGuess = spec.approxSizeMb.toLong() * 1024 * 1024
        var alreadyDone = 0L

        for (file in spec.files) {
            ensureActive()
            val target = File(root, file.relativePath)
            target.parentFile?.mkdirs()
            if (target.isFile && target.length() > 0) {
                alreadyDone += target.length()
                onProgress(alreadyDone, totalGuess, file.relativePath)
                continue
            }
            val base = alreadyDone
            downloadFile(file.remoteUrl, target) { written, _ ->
                onProgress(base + written, totalGuess, file.relativePath)
            }
            alreadyDone += target.length()
        }
        onProgress(alreadyDone, totalGuess, "")
    }

    private fun downloadFile(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val part = File(target.absolutePath + ".part")
        var startAt = if (part.isFile) part.length() else 0L
        if (startAt > 0 && target.isFile) startAt = 0 // 目标已存在则重下
        var redirects = 0
        var current = url

        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "RemoteMic/0.1")
            if (startAt > 0) conn.setRequestProperty("Range", "bytes=$startAt-")

            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                throw e
            }

            if (code == 301 || code == 302 || code == 307 || code == 308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank() || redirects++ > 5) error("重定向异常：$current")
                current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                continue
            }
            if (code != 200 && code != 206) {
                conn.disconnect()
                error("下载失败 HTTP $code：$current")
            }

            val contentLen = conn.contentLengthLong
            val total = if (code == 206) startAt + contentLen else contentLen
            val append = code == 206

            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var written = if (append) startAt else 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        onProgress(written, if (total > 0) total else -1)
                    }
                }
            }
            conn.disconnect()
            break
        }

        if (target.isFile) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }
}
