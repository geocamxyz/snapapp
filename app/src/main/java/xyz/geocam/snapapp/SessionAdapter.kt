package xyz.geocam.snapapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
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
    private val onView: (String) -> Unit
) : ListAdapter<SessionFile, SessionAdapter.ViewHolder>(DIFF) {

    private val statusOverrides = mutableMapOf<String, UploadStatus>()
    private val progressOverrides = mutableMapOf<String, Int>()
    private val projectUrlOverrides = mutableMapOf<String, String>()
    private val thumbnailCache = HashMap<Long, Bitmap?>()

    fun updateStatus(name: String, status: UploadStatus) {
        statusOverrides[name] = status
        if (status != UploadStatus.UPLOADING) progressOverrides.remove(name)
        notifyItemForName(name)
    }

    fun updateProgress(name: String, percent: Int) {
        progressOverrides[name] = percent
        notifyItemForName(name)
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
            b.textMeta.text = "$date  ·  $size  ·  $shots"

            val status = statusOverrides[item.name] ?: item.uploadStatus
            b.imageStatus.setImageResource(status.iconRes())
            b.imageStatus.contentDescription = status.name

            val uploading = status == UploadStatus.UPLOADING
            val pct = progressOverrides[item.name]
            b.buttonUpload.isEnabled = !uploading
            b.buttonUpload.text = when {
                uploading && pct != null -> "$pct%"
                uploading -> "…"
                else -> itemView.context.getString(R.string.upload)
            }
            b.buttonUpload.setOnClickListener { onUpload(item) }
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
            b.textViewResults.visibility = if (url != null) View.VISIBLE else View.GONE
            b.textViewResults.setOnClickListener { url?.let { onView(it) } }

            bindThumbnails(item)
        }

        private fun bindThumbnails(item: SessionFile) {
            val strip = b.thumbnailStrip
            strip.removeAllViews()
            if (item.shotIds.isEmpty()) return

            val ctx = strip.context
            val sizePx = (72 * ctx.resources.displayMetrics.density).toInt()
            val gapPx = (4 * ctx.resources.displayMetrics.density).toInt()

            item.shotIds.forEach { shotId ->
                val thumb = ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply { rightMargin = gapPx }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFF222222.toInt())
                    tag = shotId
                }
                strip.addView(thumb)
                loadThumbnail(thumb, shotId, item.path)
            }
        }
    }

    private fun loadThumbnail(view: ImageView, shotId: Long, sessionPath: String) {
        val cached = thumbnailCache[shotId]
        if (thumbnailCache.containsKey(shotId)) {
            view.setImageBitmap(cached)
            return
        }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val db = SessionDb.openReadOnly(File(sessionPath))
                    val bytes = db.loadThumbnail(shotId)
                    db.close()
                    bytes?.let { decodeThumbnail(it) }
                } catch (_: Exception) { null }
            }
            thumbnailCache[shotId] = bitmap
            if (view.tag == shotId) view.setImageBitmap(bitmap)
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

        private fun decodeThumbnail(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            val target = 256
            while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
                sample *= 2
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
            })
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
