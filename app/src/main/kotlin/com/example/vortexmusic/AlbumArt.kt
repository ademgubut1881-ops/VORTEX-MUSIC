package io.github.vortexmusic.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

// Kullanıcının kendi eklediği albüm kapakları (uygulamanın özel klasöründe saklanır)
object AlbumArt {

    private const val PREFS = "vortex_art"

    private val pool = Executors.newFixedThreadPool(1)
    private val main = Handler(Looper.getMainLooper())
    private var version = 0

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16L).toInt().coerceAtLeast(2 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun albumKey(album: String): String = "B|$album"
    fun artistKey(artist: String): String = "A|$artist"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun fileFor(ctx: Context, key: String): File {
        val name = Integer.toHexString(key.hashCode()) + "_" + key.length
        return File(File(ctx.filesDir, "albumart"), "$name.jpg")
    }

    // Şarkıya uygulanacak özel kapak anahtarı (yoksa null)
    fun keyFor(ctx: Context, song: Song): String? {
        val p = prefs(ctx)
        val a = albumKey(song.album)
        if (p.contains(a)) return a
        val byArtist = ctx.getSharedPreferences("vortex", Context.MODE_PRIVATE)
            .getBoolean("album_by_artist", false)
        if (byArtist) {
            val b = artistKey(song.artist)
            if (p.contains(b)) return b
        }
        return null
    }

    fun has(ctx: Context, key: String): Boolean = prefs(ctx).contains(key)

    fun load(ctx: Context, key: String, sizePx: Int, onResult: (Bitmap?) -> Unit) {
        val ck = "$key#$version#$sizePx"
        val cached = cache.get(ck)
        if (cached != null) {
            onResult(cached)
            return
        }
        val app = ctx.applicationContext
        pool.execute {
            var bmp: Bitmap? = null
            try {
                val f = fileFor(app, key)
                if (f.exists()) {
                    val bounds = BitmapFactory.Options()
                    bounds.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(f.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= sizePx &&
                        bounds.outHeight / (sample * 2) >= sizePx
                    ) {
                        sample *= 2
                    }
                    val opts = BitmapFactory.Options()
                    opts.inSampleSize = sample
                    bmp = BitmapFactory.decodeFile(f.absolutePath, opts)
                }
            } catch (e: Exception) {
                bmp = null
            }
            if (bmp != null) cache.put(ck, bmp)
            main.post { onResult(bmp) }
        }
    }

    fun save(ctx: Context, key: String, uri: Uri): Boolean {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 900 && bounds.outHeight / (sample * 2) >= 900) {
                sample *= 2
            }
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false
            val f = fileFor(ctx, key)
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            prefs(ctx).edit().putString(key, f.name).commit()
            version++
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun remove(ctx: Context, key: String) {
        try {
            fileFor(ctx, key).delete()
        } catch (e: Exception) {
        }
        prefs(ctx).edit().remove(key).commit()
        version++
    }
}