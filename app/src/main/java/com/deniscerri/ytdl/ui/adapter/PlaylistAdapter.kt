package com.deniscerri.ytdl.ui.adapter

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.paging.PagingDataAdapter
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.util.AccessibilityUtil
import com.deniscerri.ytdl.util.BrowserMediaUtil
import com.deniscerri.ytdl.util.Extensions.loadThumbnail
import com.deniscerri.ytdl.util.Extensions.popup
import com.google.android.material.card.MaterialCardView
import org.junit.internal.Checks


class PlaylistAdapter(onItemClickListener: OnItemClickListener, activity: Activity) : PagingDataAdapter<ResultItem, PlaylistAdapter.ViewHolder>(DIFF_CALLBACK) {
    val checkedItems: ArrayList<Long>
    var inverted: Boolean
    private val onItemClickListener: OnItemClickListener
    private val activity: Activity
    private val sharedPreferences: SharedPreferences

    init {
        checkedItems = ArrayList()
        this.inverted = false
        this.onItemClickListener = onItemClickListener
        this.activity = activity
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity)
    }

    class ViewHolder(itemView: View, onItemClickListener: OnItemClickListener?) : RecyclerView.ViewHolder(itemView) {
        val cardView: MaterialCardView

        init {
            cardView = itemView.findViewById(R.id.playlist_card)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val cardView = LayoutInflater.from(parent.context)
                .inflate(R.layout.playlist_item, parent, false)
        return ViewHolder(cardView, onItemClickListener)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position) ?: return

        val card = holder.cardView
        card.popup()
        val uiHandler = Handler(Looper.getMainLooper())
        val thumbnail = card.findViewById<ImageView>(R.id.downloads_image_view)

        // THUMBNAIL ----------------------------------
        val hideThumb = sharedPreferences.getStringSet("hide_thumbnails", emptySet())!!.contains("home")
        uiHandler.post { thumbnail.loadThumbnail(hideThumb, item!!.thumb) }

        val isImage = BrowserMediaUtil.isImageOnlyResult(item)
        val indexNum = item.playlistIndex ?: (position + 1)
        card.findViewById<TextView>(R.id.title).text = item!!.title
        card.findViewById<TextView>(R.id.author).text = item.author
        card.findViewById<TextView>(R.id.duration).text = if (isImage && item.duration.isBlank()) {
            activity.getString(R.string.image)
        } else {
            item.duration
        }
        card.findViewById<TextView>(R.id.index).text = indexNum.toString()

        // CHECKBOX ----------------------------------
        val check = card.findViewById<CheckBox>(R.id.checkBox)
        val isCheckedNow = (checkedItems.contains(item.id) && !inverted) || (!checkedItems.contains(item.id) && inverted)
        check.isChecked = isCheckedNow

        val mediaTypeLabel = activity.getString(if (isImage) R.string.image else R.string.video)
        card.contentDescription = "$indexNum. $mediaTypeLabel: ${item.title}"
        AccessibilityUtil.setStateDescription(
            card,
            activity.getString(if (isCheckedNow) R.string.state_selected else R.string.state_unselected)
        )

        check.setOnClickListener {
            checkCard(check, item.id, card)
        }

        card.setOnClickListener {
            check.performClick()
        }
    }

    private fun checkCard(check: CheckBox, id: Long, card: MaterialCardView) {
        if (!check.isChecked) {
            if (inverted) checkedItems.add(id)
            else checkedItems.remove(id)
        } else {
            if (inverted) checkedItems.remove(id)
            else checkedItems.add(id)
        }
        AccessibilityUtil.setStateDescription(
            card,
            activity.getString(if (check.isChecked) R.string.state_selected else R.string.state_unselected)
        )
        onItemClickListener.onCardSelect(id, check.isChecked)
    }

    interface OnItemClickListener {
        fun onCardSelect(itemID: Long, isChecked: Boolean)
    }

    fun clearCheckeditems() {
        inverted = false
        checkedItems.clear()
        notifyItemRangeChanged(0, itemCount)
    }

    fun checkMultipleItems(list: List<Long>){
        checkedItems.clear()
        inverted = false
        checkedItems.addAll(list)
        notifyItemRangeChanged(0, itemCount)
    }

    fun checkAll(){
        checkedItems.clear()
        inverted = true
        notifyItemRangeChanged(0, itemCount)
    }

    fun invertSelected(){
        inverted = !inverted
        notifyItemRangeChanged(0, itemCount)
    }

    companion object {
        private val DIFF_CALLBACK: DiffUtil.ItemCallback<ResultItem> = object : DiffUtil.ItemCallback<ResultItem>() {
            override fun areItemsTheSame(oldItem: ResultItem, newItem: ResultItem): Boolean {
                val ranged = arrayListOf(oldItem.id, newItem.id)
                return ranged[0] == ranged[1]
            }

            override fun areContentsTheSame(oldItem: ResultItem, newItem: ResultItem): Boolean {
                return oldItem.id == newItem.id
            }
        }
    }
}