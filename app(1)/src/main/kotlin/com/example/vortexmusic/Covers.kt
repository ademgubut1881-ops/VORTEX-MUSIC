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
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val bytes = r.embeddedPicture ?: return null
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
        } catch (e: Exception) {
            return null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
    }
}