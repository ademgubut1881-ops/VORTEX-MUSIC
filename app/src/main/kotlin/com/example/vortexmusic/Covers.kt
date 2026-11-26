package io.github.vortexmusic.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
object Covers {

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8L).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val missing = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    // Kapak hazırsa hemen, değilse arka planda yükleyip ana iş parçacığında döndürür.
    fun load(context: Context, songId: Long, uri: Uri, sizePx: Int, onResult: (Bitmap?) -> Unit) {
        val key = "$songId:$sizePx"
        val cached = cache.get(key)
        if (cached != null) {
            onResult(cached)
            return
        }
        val isMissing = synchronized(missing) { missing.contains(key) }
        if (isMissing) {
            onResult(null)
            return
        }
        val app = context.applicationContext
        pool.execute {
            val bmp = fetch(app, uri, sizePx)
            if (bmp != null) {
                cache.put(key, bmp)
            } else {
                synchronized(missing) { missing.add(key) }
            }
            main.post { onResult(bmp) }
        }
    }

    private fun fetch(context: Context, uri: Uri, size: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return context.contentResolver.loadThumbnail(uri, Size(size, size), null)
            } catch (e: Exception) {
            }
        }
        val fromRetriever = retrieverPicture(context, uri, size)
        if (fromRetriever != null) return fromRetriever
        // Opus / Ogg dosyalarında kapak etikette durur, Android bunu kendisi okumaz
        return oggPicture(context, uri, size)
    }

    private fun retrieverPicture(context: Context, uri: Uri, size: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val bytes = r.embeddedPicture ?: return null
            return decodeSampled(bytes, size)
        } catch (e: Exception) {
            return null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun decodeSampled(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) {
            sample *= 2
        }
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun readFully(s: InputStream, b: ByteArray): Boolean {
        var n = 0
        while (n < b.size) {
            val r = s.read(b, n, b.size - n)
            if (r < 0) return false
            n += r
        }
        return true
    }

    private fun le32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

    private fun be32(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)

    // Ogg (Opus/Vorbis): ikinci paket yorum başlığıdır; kapak METADATA_BLOCK_PICTURE etiketindedir
    private fun oggPicture(context: Context, uri: Uri, size: Int): Bitmap? {
        try {
            val raw = context.contentResolver.openInputStream(uri) ?: return null
            raw.use { r0 ->
                val ins = BufferedInputStream(r0, 64 * 1024)
                var packet = ByteArrayOutputStream()
                var count = 0
                var comment: ByteArray? = null
                var pages = 0
                while (comment == null && pages < 600) {
                    val h = ByteArray(27)
                    if (!readFully(ins, h)) return null
                    if (h[0] != 'O'.code.toByte() || h[1] != 'g'.code.toByte() ||
                        h[2] != 'g'.code.toByte() || h[3] != 'S'.code.toByte()
                    ) return null
                    val nSeg = h[26].toInt() and 0xFF
                    val seg = ByteArray(nSeg)
                    if (!readFully(ins, seg)) return null
                    var total = 0
                    for (b in seg) total += b.toInt() and 0xFF
                    val body = ByteArray(total)
                    if (!readFully(ins, body)) return null
                    var pos = 0
                    for (b in seg) {
                        val len = b.toInt() and 0xFF
                        packet.write(body, pos, len)
                        pos += len
                        if (len < 255) {
                            count++
                            if (count == 2) {
                                comment = packet.toByteArray()
                                break
                            }
                            packet = ByteArrayOutputStream()
                        }
                    }
                    if (packet.size() > 8 * 1024 * 1024) return null
                    pages++
                }
                val c = comment ?: return null
                var p = when {
                    c.size > 8 && String(c, 0, 8, Charsets.ISO_8859_1) == "OpusTags" -> 8
                    c.size > 7 && c[0].toInt() == 3 && String(c, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> 7
                    else -> return null
                }
                if (p + 4 > c.size) return null
                p += 4 + le32(c, p)
                if (p + 4 > c.size) return null
                val n = le32(c, p)
                p += 4
                for (i in 0 until n) {
                    if (p + 4 > c.size) return null
                    val len = le32(c, p)
                    p += 4
                    if (len < 0 || p + len > c.size) return null
                    val text = String(c, p, len, Charsets.UTF_8)
                    p += len
                    val eq = text.indexOf('=')
                    if (eq <= 0) continue
                    val key = text.substring(0, eq).uppercase()
                    if (key == "METADATA_BLOCK_PICTURE") {
                        val pic = Base64.decode(text.substring(eq + 1), Base64.DEFAULT)
                        var q = 4
                        if (q + 4 > pic.size) continue
                        q += 4 + be32(pic, q)
                        if (q + 4 > pic.size) continue
                        q += 4 + be32(pic, q)
                        q += 16
                        if (q + 4 > pic.size) continue
                        val dl = be32(pic, q)
                        q += 4
                        if (dl <= 0 || q + dl > pic.size) continue
                        return decodeSampled(pic.copyOfRange(q, q + dl), size)
                    } else if (key == "COVERART") {
                        return decodeSampled(Base64.decode(text.substring(eq + 1), Base64.DEFAULT), size)
                    }
                }
            }
        } catch (e: Exception) {
        } catch (e: OutOfMemoryError) {
        }
        return null
    }
}
