package xyz.geocam.snapapp

import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.geocam.snapapp.data.SessionFile
import xyz.geocam.snapapp.data.UploadStatus
import xyz.geocam.snapapp.databinding.ItemSessionBinding
import xyz.geocam.snapapp.db.SessionDb
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionAdapter(
    private val scope: CoroutineScope,
    private val onUpload: (SessionFile) -> Unit,
    private val onShare: (SessionFile) -> Unit,
    private val onView: (String) -> Unit,
    private val onDelete: (SessionFile) -> Unit,
    private val isUploading: (String) -> Boolean,
    private val onDeleteShot: (SessionFile, Long) -> Unit
) : ListAdapter<SessionFile, SessionAdapter.ViewHolder>(DIFF) {

    private val statusOverrides = mutableMapOf<String, UploadStatus>()
    private val progressOverrides = mutableMapOf<String, Int>()
    private val projectUrlOverrides = mutableMapOf<String, String>()
    // Cache key = "$sessionPath:$shotId" to avoid cross-session collisions
    private val thumbnailCache = HashMap<String, Bitmap?>()
    private val deleteMode = mutableSetOf<String>()
    private val thumbnailDeleteMode = mutableSetOf<String>()

    fun updateStatus(name: String, status: UploadStatus) {
        statusOverrides[name] = status
        if (status != UploadStatus.UPLOADING) progressOverrides.remove(name)
        notifyItemForName(name)
    }

    fun updateProgress(name: String, percent: Int) {
        progressOverrides[name] = percent
        notifyItemForName(name)
    }

    fun clearThumbnailCache() {
        thumbnailCache.clear()
    }

    fun updateProjectUrl(name: String, url: String) {
        projectUrlOverrides[name] = url
        notifyItemForName(name)
    }

    private fun notifyItemForName(name: String) {
        val idx = currentList.indexOfFirst { it.name == name }
        if (idx >= 0) notifyItemChanged(idx)
    }

    inner class ViewHolder(private val b: ItemSessionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: SessionFile) {
            b.textName.text = item.name.removeSuffix(".db")

            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.lastModified))
            val size = formatSize(item.fileSizeBytes)
            val shots = "${item.shotCount} shot${if (item.shotCount == 1) "" else "s"}"
            val scan = if (item.wideScanFrameIds.isNotEmpty()) "  ·  ${item.wideScanFrameIds.size} scan" else ""
            b.textMeta.text = "$date  ·  $size  ·  $shots$scan"

            val status = statusOverrides[item.name] ?: item.uploadStatus
            val activelyUploading = isUploading(item.name)

            b.imageStatus.setImageResource(status.iconRes())
            b.imageStatus.contentDescription = status.name
            // Tapping the status icon retries when not currently uploading
            b.imageStatus.setOnClickListener {
                if (!activelyUploading) onUpload(item)
            }

            val inDelete = item.name in deleteMode
            val pct = progressOverrides[item.name]

            if (inDelete) {
                b.buttonUpload.text = itemView.context.getString(R.string.delete)
                b.buttonUpload.isEnabled = true
                b.buttonUpload.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_error))
                b.buttonUpload.setOnClickListener {
                    deleteMode.remove(item.name)
                    onDelete(item)
                }
            } else {
                b.buttonUpload.setTextColor(ContextCompat.getColor(itemView.context, R.color.colorPrimary))
                b.buttonUpload.isEnabled = !activelyUploading
                b.buttonUpload.text = when {
                    activelyUploading && pct != null -> "$pct%"
                    activelyUploading -> "…"
                    else -> itemView.context.getString(R.string.upload)
                }
                b.buttonUpload.setOnClickListener { onUpload(item) }
            }

            b.root.setOnLongClickListener {
                if (item.name in deleteMode) deleteMode.remove(item.name)
                else deleteMode.add(item.name)
                notifyItemForName(item.name)
                true
            }

            b.buttonShare.setOnClickListener { onShare(item) }

            val hasLocation = item.firstLat != null && item.firstLon != null
            b.buttonMap.isEnabled = hasLocation
            b.buttonMap.alpha = if (hasLocation) 1f else 0.3f
            b.buttonMap.setOnClickListener {
                val lat = item.firstLat ?: return@setOnClickListener
                val lon = item.firstLon ?: return@setOnClickListener
                val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${item.name.removeSuffix(".db")})")
                itemView.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }

            val url = projectUrlOverrides[item.name] ?: item.projectUrl
            b.textViewResults.visibility = if (url != null) android.view.View.VISIBLE else android.view.View.GONE
            b.textViewResults.setOnClickListener { url?.let { onView(it) } }

            bindThumbnails(item)
        }

        private fun bindThumbnails(item: SessionFile) {
            val strip = b.thumbnailStrip
            strip.removeAllViews()
            if (item.shotIds.isEmpty() && item.wideScanFrameIds.isEmpty()) return

            val ctx = strip.context
            val sizePx = (72 * ctx.resources.displayMetrics.density).toInt()
            val gapPx = (4 * ctx.resources.displayMetrics.density).toInt()

            // Burst shot thumbnails (long-press to delete)
            val burstEntries = item.shotIds.map { shotId ->
                val key = cacheKey(item.path, shotId)
                val inDeleteMode = thumbnailDeleteMode.contains(key)
                val thumb = ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply { rightMargin = gapPx }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    tag = key
                    if (inDeleteMode) {
                        setImageResource(R.drawable.ic_delete_shot)
                        setBackgroundColor(0xFFCC2200.toInt())
                    } else {
                        setBackgroundColor(0xFF2A2A2A.toInt())
                        if (thumbnailCache.containsKey(key)) setImageBitmap(thumbnailCache[key])
                    }
                }
                thumb.setOnLongClickListener {
                    val nowDelete = !thumbnailDeleteMode.contains(key)
                    if (nowDelete) thumbnailDeleteMode.add(key) else thumbnailDeleteMode.remove(key)
                    flipThumb(thumb, toDelete = nowDelete, bitmap = thumbnailCache[key])
                    true
                }
                thumb.setOnClickListener {
                    if (thumbnailDeleteMode.contains(key)) onDeleteShot(item, shotId)
                }
                strip.addView(thumb)
                key to shotId
            }

            // Wide scan frame thumbnails (display only, capped at 8)
            val scanIds = item.wideScanFrameIds.take(8)
            val scanEntries = scanIds.map { frameId ->
                val key = "scan:${cacheKey(item.path, frameId)}"
                val thumb = ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply { rightMargin = gapPx }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    tag = key
                    setBackgroundColor(0xFF1A2A1A.toInt()) // dark green tint to distinguish
                    if (thumbnailCache.containsKey(key)) setImageBitmap(thumbnailCache[key])
                }
                strip.addView(thumb)
                key to frameId
            }

            val uncachedBurst = burstEntries
                .filter { (key, _) -> !thumbnailCache.containsKey(key) && !thumbnailDeleteMode.contains(key) }
                .map { (_, shotId) -> shotId }
            val uncachedScan = scanEntries
                .filter { (key, _) -> !thumbnailCache.containsKey(key) }
                .map { (_, frameId) -> frameId }

            if (uncachedBurst.isEmpty() && uncachedScan.isEmpty()) return

            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    try {
                        SessionDb.openReadOnly(File(item.path)).use { db ->
                            val burst = uncachedBurst.associateWith { shotId ->
                                db.loadThumbnail(shotId)?.let { decodeThumbnail(it) }
                            }
                            val scan = uncachedScan.associateWith { frameId ->
                                db.loadWideScanThumbnail(frameId)?.let { decodeThumbnail(it) }
                            }
                            burst to scan
                        }
                    } catch (e: Exception) {
                        emptyMap<Long, Bitmap?>() to emptyMap<Long, Bitmap?>()
                    }
                }
                loaded.first.forEach { (shotId, bitmap) ->
                    val key = cacheKey(item.path, shotId)
                    thumbnailCache[key] = bitmap
                    if (!thumbnailDeleteMode.contains(key)) {
                        strip.findViewWithTag<ImageView>(key)?.setImageBitmap(bitmap)
                    }
                }
                loaded.second.forEach { (frameId, bitmap) ->
                    val key = "scan:${cacheKey(item.path, frameId)}"
                    thumbnailCache[key] = bitmap
                    strip.findViewWithTag<ImageView>(key)?.setImageBitmap(bitmap)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<SessionFile>() {
            override fun areItemsTheSame(a: SessionFile, b: SessionFile) = a.name == b.name
            override fun areContentsTheSame(a: SessionFile, b: SessionFile) = a == b
        }

        private fun cacheKey(sessionPath: String, shotId: Long) = "$sessionPath:$shotId"

        private fun flipThumb(view: ImageView, toDelete: Boolean, bitmap: Bitmap?) {
            ObjectAnimator.ofFloat(view, "rotationY", 0f, 90f).apply {
                duration = 140
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (toDelete) {
                            view.setImageResource(R.drawable.ic_delete_shot)
                            view.setBackgroundColor(0xFFCC2200.toInt())
                        } else {
                            view.setImageBitmap(bitmap)
                            view.setBackgroundColor(0xFF2A2A2A.toInt())
                        }
                        view.rotationY = -90f
                        ObjectAnimator.ofFloat(view, "rotationY", -90f, 0f).apply {
                            duration = 140
                        }.start()
                    }
                })
            }.start()
        }

        private fun decodeThumbnail(bytes: ByteArray): Bitmap? = try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        }

        private fun formatSize(bytes: Long) = when {
            bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}

private fun UploadStatus.iconRes() = when (this) {
    UploadStatus.UPLOADED -> R.drawable.ic_status_uploaded
    UploadStatus.UPLOADING -> R.drawable.ic_status_uploading
    UploadStatus.ERROR -> R.drawable.ic_status_error
    UploadStatus.PENDING -> R.drawable.ic_status_pending
}
