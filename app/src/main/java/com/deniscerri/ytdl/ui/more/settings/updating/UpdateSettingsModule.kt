package com.deniscerri.ytdl.ui.more.settings.updating

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.BuildConfig
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.ui.more.settings.SettingHost
import com.deniscerri.ytdl.ui.more.settings.SettingModule
import com.deniscerri.ytdl.util.ApkInstallUtil
import com.deniscerri.ytdl.util.UiUtil
import com.deniscerri.ytdl.util.UpdateUtil
import com.deniscerri.ytdl.util.rules.WebRulesRegistry
import com.deniscerri.ytdl.work.background.UpdateCheckWorker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

object UpdateSettingsModule : SettingModule {
    override fun bindLogic(pref: Preference, host: SettingHost) {
        val context = pref.context
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)

        when (pref.key) {
            "ytdlp_update_screen" -> {
                pref.apply {
                    val version = preferences.getString("ytdl-version", "")!!
                    val source = preferences.getString("ytdlp_source", "nightly")!!

                    val channel = when (source) {
                        "stable" -> context.getString(R.string.channel_stable)
                        "nightly" -> context.getString(R.string.channel_nightly)
                        "master" -> context.getString(R.string.channel_master)
                        else -> preferences.getString("ytdlp_source_label", "")!!.ifBlank { source }
                    }
                    summary = "$version (${channel})"
                    setOnPreferenceClickListener {
                        host.requestNavigate(R.id.ytdlpUpdateSettingsFragment)
                        true
                    }
                }
            }
            "app_update_screen" -> {
                pref.apply {
                    summary = if (UpdateUtil.isAppSelfUpdateConfigured()) {
                        "${BuildConfig.VERSION_NAME} (${File(context.applicationInfo.nativeLibraryDir).name}) (${BuildConfig.FLAVOR})"
                    } else {
                        "${BuildConfig.VERSION_NAME} · ${context.getString(R.string.app_update_manual_notice)}"
                    }
                    setOnPreferenceClickListener {
                        host.requestNavigate(R.id.appUpdateSettingsFragment)
                        true
                    }
                }
            }
            "packages" -> {
                pref.apply {
                    summary = "Python, FFmpeg, Aria2c, NodeJS, Deno"
                    setOnPreferenceClickListener {
                        host.requestNavigate(R.id.packagesFragment)
                        false
                    }
                }
            }
            "web_rules_manage" -> {
                pref.apply {
                    summary = formatRulesSummary(context)
                    setOnPreferenceClickListener {
                        showWebRulesDialog(host.getHostContext(), pref)
                        true
                    }
                }
            }
            UpdateCheckWorker.PREF_ENABLED -> {
                pref.setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putBoolean(UpdateCheckWorker.PREF_ENABLED, newValue as Boolean).commit()
                    UpdateCheckWorker.sync(context)
                    true
                }
            }
            UpdateCheckWorker.PREF_TYPES -> {
                (pref as MultiSelectListPreference).apply {
                    if (!UpdateUtil.isAppSelfUpdateConfigured() || BuildConfig.FLAVOR != "github") {
                        entries = entries.drop(1).toTypedArray()
                        entryValues = entryValues.drop(1).toTypedArray()
                    }
                    fun setSummary(selected: Set<String>) {
                        summary = entryValues.indices.filter { selected.contains(entryValues[it]) }
                            .joinToString(", ") { entries[it] }
                            .ifEmpty { context.getString(R.string.notset) }
                    }
                    setSummary(values)
                    setOnPreferenceChangeListener { _, newValue ->
                        @Suppress("UNCHECKED_CAST")
                        setSummary(newValue as Set<String>)
                        true
                    }
                }
            }
            "apk_install_method" -> {
                pref.apply {
                    summary = ApkInstallUtil.getSelectedInstallerLabel(context)
                    setOnPreferenceClickListener {
                        ApkInstallUtil.showInstallerPickerDialog(host.getHostContext()) { picked ->
                            if (picked) summary = ApkInstallUtil.getSelectedInstallerLabel(context)
                        }
                        true
                    }
                }
            }
        }
    }

    private fun formatRulesSummary(context: Context): String {
        val ver = WebRulesRegistry.current().rulesVersion
        return if (WebRulesRegistry.isUsingOverride()) {
            context.getString(R.string.web_rules_summary_override, ver)
        } else {
            context.getString(R.string.web_rules_summary_bundled, ver)
        }
    }

    private fun showWebRulesDialog(hostContext: Activity, pref: Preference) {
        val cfg = WebRulesRegistry.current()
        val isOverride = WebRulesRegistry.isUsingOverride()
        val sourceLabel = if (isOverride) {
            hostContext.getString(R.string.web_rules_source_override)
        } else {
            hostContext.getString(R.string.web_rules_source_bundled)
        }
        val msg = hostContext.getString(
            R.string.web_rules_dialog_message,
            cfg.rulesVersion,
            sourceLabel,
            cfg.quickPlatforms.size,
            cfg.domains.displayNames.size
        )

        val builder = MaterialAlertDialogBuilder(hostContext)
            .setTitle(R.string.web_rules_title)
            .setMessage(msg)
            .setPositiveButton(R.string.web_rules_import_json) { _, _ ->
                showImportRulesJsonDialog(hostContext, pref)
            }
            .setNeutralButton(R.string.web_rules_copy_json) { _, _ ->
                UiUtil.copyToClipboard(WebRulesRegistry.exportCurrentJson(), hostContext)
            }

        if (isOverride) {
            builder.setNegativeButton(R.string.web_rules_reset_bundled) { _, _ ->
                WebRulesRegistry.resetOverride(hostContext)
                pref.summary = formatRulesSummary(pref.context)
                Toast.makeText(hostContext, R.string.web_rules_reset_done, Toast.LENGTH_SHORT).show()
            }
        } else {
            builder.setNegativeButton(R.string.cancel, null)
        }
        builder.show()
    }

    private fun showImportRulesJsonDialog(hostContext: Context, pref: Preference) {
        val input = EditText(hostContext).apply {
            hint = hostContext.getString(R.string.web_rules_import_hint)
            minLines = 6
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        val clipboard = hostContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(hostContext)?.toString().orEmpty().trim()
        if (clipText.startsWith("{") && WebRulesRegistry.parseJson(clipText).isSuccess) {
            input.setText(clipText)
        }

        val pad = (16 * hostContext.resources.displayMetrics.density).toInt()
        val container = ScrollView(hostContext).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(hostContext)
            .setTitle(R.string.web_rules_import_json)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val raw = input.text?.toString().orEmpty()
                val res = WebRulesRegistry.importOverrideJson(hostContext, raw)
                if (res.isSuccess) {
                    val updated = res.getOrThrow()
                    pref.summary = formatRulesSummary(pref.context)
                    Toast.makeText(
                        hostContext,
                        hostContext.getString(R.string.web_rules_import_success, updated.rulesVersion),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val errMsg = res.exceptionOrNull()?.message.orEmpty()
                    Toast.makeText(
                        hostContext,
                        hostContext.getString(R.string.web_rules_import_error, errMsg),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }
}
