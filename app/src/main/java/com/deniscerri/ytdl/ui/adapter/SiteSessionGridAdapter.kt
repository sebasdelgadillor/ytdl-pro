package com.deniscerri.ytdl.ui.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.models.SiteSession
import com.deniscerri.ytdl.util.AccessibilityUtil
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.squareup.picasso.Picasso
import java.io.File
import java.util.Locale
import kotlin.math.abs

class SiteSessionGridAdapter(
    private val onSiteClick: (SiteSession) -> Unit,
    private val onSiteLongClick: (SiteSession) -> Unit,
    private val onAddClick: () -> Unit
) : ListAdapter<SiteSessionGridAdapter.GridItem, SiteSessionGridAdapter.ViewHolder>(DIFF_CALLBACK) {

    sealed class GridItem {
        data class SessionItem(val session: SiteSession) : GridItem()
        data object AddButton : GridItem()
    }

    var compactStrip: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    fun submitSessions(sessions: List<SiteSession>, includeAddTile: Boolean) {
        val items = mutableListOf<GridItem>()
        sessions.forEach { items.add(GridItem.SessionItem(it)) }
        if (includeAddTile) {
            items.add(GridItem.AddButton)
        }
        submitList(items)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_site_session_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context
        val lp = holder.card.layoutParams
        lp.width = if (compactStrip) {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                88f,
                ctx.resources.displayMetrics
            ).toInt()
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        holder.card.layoutParams = lp

        when (item) {
            is GridItem.SessionItem -> {
                val session = item.session
                holder.name.text = session.displayName
                holder.statusDot.isVisible = session.isLoggedIn
                holder.icon.imageTintList = null

                val fallback = createMonogramDrawable(ctx, session.displayName, session.domain)
                val localFile = session.localIconPath.takeIf { it.isNotBlank() }?.let { File(it) }
                if (localFile != null && localFile.exists() && localFile.length() > 0L) {
                    val bmp = BitmapFactory.decodeFile(localFile.absolutePath)
                    if (bmp != null) {
                        holder.icon.setImageBitmap(bmp)
                    } else {
                        holder.icon.setImageDrawable(fallback)
                    }
                } else if (session.faviconUrl.startsWith("http")) {
                    Picasso.get()
                        .load(session.faviconUrl)
                        .placeholder(fallback)
                        .error(fallback)
                        .into(holder.icon)
                } else {
                    holder.icon.setImageDrawable(fallback)
                }

                holder.card.contentDescription = if (session.isLoggedIn) {
                    ctx.getString(R.string.session_item_active_desc, session.displayName)
                } else {
                    ctx.getString(R.string.session_item_inactive_desc, session.displayName)
                }
                AccessibilityUtil.setClickLabel(
                    holder.card,
                    ctx.getString(R.string.open_platform_in_browser, session.displayName)
                )
                AccessibilityUtil.setLongClickLabel(
                    holder.card,
                    ctx.getString(R.string.session_item_long_click_label)
                )

                holder.card.setOnClickListener { onSiteClick(session) }
                holder.card.setOnLongClickListener {
                    onSiteLongClick(session)
                    true
                }
            }

            is GridItem.AddButton -> {
                holder.name.setText(R.string.add_platform)
                holder.statusDot.isVisible = false
                holder.icon.setImageResource(R.drawable.ic_plus)
                val primaryColor = MaterialColors.getColor(
                    ctx,
                    androidx.appcompat.R.attr.colorPrimary,
                    Color.GRAY
                )
                holder.icon.imageTintList = ColorStateList.valueOf(primaryColor)
                holder.card.contentDescription = ctx.getString(R.string.open_browser)
                AccessibilityUtil.setClickLabel(holder.card, ctx.getString(R.string.open_browser))
                AccessibilityUtil.setLongClickLabel(holder.card, null)
                holder.card.setOnClickListener { onAddClick() }
                holder.card.setOnLongClickListener(null)
            }
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.site_card)
        val icon: ImageView = view.findViewById(R.id.site_icon)
        val statusDot: View = view.findViewById(R.id.site_status_dot)
        val name: TextView = view.findViewById(R.id.site_name)
    }

    private fun createMonogramDrawable(context: Context, title: String, domain: String): Drawable {
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            40f,
            context.resources.displayMetrics
        ).toInt().coerceAtLeast(48)
        val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val palette = intArrayOf(
            0xFFE1306C.toInt(),
            0xFF1877F2.toInt(),
            0xFF1DA1F2.toInt(),
            0xFFFF4500.toInt(),
            0xFF6750A4.toInt(),
            0xFF00897B.toInt()
        )
        val bgColor = palette[abs(domain.hashCode()) % palette.size]
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, bgPaint)

        val letter = title.trim().firstOrNull()?.uppercase(Locale.US)
            ?: domain.firstOrNull()?.uppercase(Locale.US) ?: "?"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * 0.48f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val yPos = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(letter, sizePx / 2f, yPos, textPaint)
        return BitmapDrawable(context.resources, bmp)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<GridItem>() {
            override fun areItemsTheSame(oldItem: GridItem, newItem: GridItem): Boolean {
                return when {
                    oldItem is GridItem.AddButton && newItem is GridItem.AddButton -> true
                    oldItem is GridItem.SessionItem && newItem is GridItem.SessionItem ->
                        oldItem.session.domain == newItem.session.domain
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: GridItem, newItem: GridItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
