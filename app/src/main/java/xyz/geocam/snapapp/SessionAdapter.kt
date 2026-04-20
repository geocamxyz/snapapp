package xyz.geocam.snapapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import xyz.geocam.snapapp.data.SessionFile
import xyz.geocam.snapapp.data.UploadStatus
import xyz.geocam.snapapp.databinding.ItemSessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionAdapter(
    private val onUpload: (SessionFile) -> Unit,
    private val onShare: (SessionFile) -> Unit
) : ListAdapter<SessionFile, SessionAdapter.ViewHolder>(DIFF) {

    private val statusOverrides = mutableMapOf<String, UploadStatus>()

    fun updateStatus(name: String, status: UploadStatus) {
        statusOverrides[name] = status
        val idx = currentList.indexOfFirst { it.name == name }
        if (idx >= 0) notifyItemChanged(idx)
    }

    inner class ViewHolder(private val b: ItemSessionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: SessionFile) {
            b.textName.text = item.name.removeSuffix(".db")
            b.textDate.text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(item.lastModified))
            val status = statusOverrides[item.name] ?: item.uploadStatus
            b.imageStatus.setImageResource(status.iconRes())
            b.imageStatus.contentDescription = status.name
            val uploading = status == UploadStatus.UPLOADING
            b.buttonUpload.isEnabled = !uploading
            b.buttonUpload.text = if (uploading) "Uploading…" else "Upload"
            b.buttonUpload.setOnClickListener { onUpload(item) }
            b.buttonShare.setOnClickListener { onShare(item) }
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
    }
}

private fun UploadStatus.iconRes() = when (this) {
    UploadStatus.UPLOADED -> R.drawable.ic_status_uploaded
    UploadStatus.UPLOADING -> R.drawable.ic_status_uploading
    UploadStatus.ERROR -> R.drawable.ic_status_error
    UploadStatus.PENDING -> R.drawable.ic_status_pending
}
