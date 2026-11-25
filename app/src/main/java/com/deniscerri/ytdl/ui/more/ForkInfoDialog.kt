package com.deniscerri.ytdl.ui.more

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.deniscerri.ytdl.BuildConfig
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.util.AccessibilityUtil
import com.deniscerri.ytdl.util.rules.WebRulesRegistry
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object ForkInfoDialog {

    fun show(context: Context) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        val noticeCard = MaterialCardView(context).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            setContentPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val noticeText = TextView(context).apply {
            text = context.getString(R.string.fork_attribution_notice)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.2f)
        }
        noticeCard.addView(noticeText)
        root.addView(
            noticeCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )

        val metaView = TextView(context).apply {
            val rulesVer = WebRulesRegistry.current().rulesVersion
            val sourceLabel = if (WebRulesRegistry.isUsingOverride()) {
                context.getString(R.string.web_rules_source_override)
            } else {
                context.getString(R.string.web_rules_source_bundled)
            }
            text = context.getString(
                R.string.fork_version_metadata,
                BuildConfig.VERSION_NAME,
                BuildConfig.APPLICATION_ID,
                rulesVer,
                sourceLabel
            )
            alpha = 0.8f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(2), 0, dp(2), dp(14))
        }
        root.addView(metaView)

        val changesHeading = TextView(context).apply {
            text = context.getString(R.string.fork_changes_heading)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        AccessibilityUtil.setHeading(changesHeading, true)
        root.addView(changesHeading)

        val changesBody = TextView(context).apply {
            text = context.getString(R.string.fork_changes_body)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(2), 0, dp(2), dp(12))
        }
        root.addView(changesBody)

        val licenseNote = TextView(context).apply {
            text = context.getString(R.string.fork_license_note)
            alpha = 0.78f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(2), 0, dp(2), dp(4))
        }
        root.addView(licenseNote)

        val scroll = ScrollView(context).apply {
            addView(root)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.about_fork_title)
            .setIcon(R.drawable.ic_info)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
