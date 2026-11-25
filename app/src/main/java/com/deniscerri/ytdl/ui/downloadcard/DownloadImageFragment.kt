package com.deniscerri.ytdl.ui.downloadcard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.util.AccessibilityUtil
import com.deniscerri.ytdl.util.BrowserMediaUtil
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.ImageDownloadHelper
import com.deniscerri.ytdl.util.QualityCapsuleUtil
import com.deniscerri.ytdl.util.UiUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class DownloadImageFragment(
    private var resultItem: ResultItem? = null,
    private var currentDownloadItem: DownloadItem? = null,
    private var url: String = ""
) : Fragment(), GUISync {

    private lateinit var downloadViewModel: DownloadViewModel
    lateinit var downloadItem: DownloadItem

    private lateinit var imagePreview: ImageView
    private lateinit var imagePreviewEmpty: LinearLayout
    private lateinit var formatCapsules: ChipGroup
    private lateinit var selectedSummary: TextView
    private lateinit var toggleAdvancedBtn: MaterialButton
    private lateinit var advancedContainer: LinearLayout
    private lateinit var titleInput: TextInputLayout
    private lateinit var saveDirInput: TextInputLayout
    private lateinit var freeSpaceText: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]
        return inflater.inflate(R.layout.fragment_download_image, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        imagePreview = view.findViewById(R.id.image_preview)
        imagePreviewEmpty = view.findViewById(R.id.image_preview_empty)
        formatCapsules = view.findViewById(R.id.image_format_capsules)
        selectedSummary = view.findViewById(R.id.image_selected_summary)
        toggleAdvancedBtn = view.findViewById(R.id.btn_toggle_advanced_image)
        advancedContainer = view.findViewById(R.id.image_advanced_container)
        titleInput = view.findViewById(R.id.title_textinput)
        saveDirInput = view.findViewById(R.id.outputPath)
        freeSpaceText = view.findViewById(R.id.freespace)

        lifecycleScope.launch {
            downloadItem = withContext(Dispatchers.IO) {
                if (savedInstanceState?.containsKey("updated") == true && ::downloadItem.isInitialized) {
                    downloadItem.apply {
                        val nextTitle = resultItem?.title.orEmpty()
                        if (nextTitle.isNotBlank() &&
                            (title.isBlank() || BrowserMediaUtil.isGenericPlaceholderTitle(title) || !BrowserMediaUtil.isGenericPlaceholderTitle(nextTitle))
                        ) {
                            title = nextTitle
                        }
                        author = resultItem?.author?.ifBlank { author } ?: author
                        website = resultItem?.website?.ifBlank { website } ?: website
                        url = resultItem?.url?.ifBlank { url } ?: url
                        val resolvedImg = BrowserMediaUtil.resolveBestImageUrl(resultItem)
                            ?: resultItem?.thumb?.takeIf { it.isNotBlank() }
                        if (!resolvedImg.isNullOrBlank()) {
                            thumb = resolvedImg
                            format.url = resolvedImg
                        } else if (format.url.isNullOrBlank() && thumb.isNotBlank()) {
                            format.url = thumb
                        }
                    }
                } else if (currentDownloadItem != null && currentDownloadItem?.type == DownloadType.image) {
                    val json = Gson().toJson(currentDownloadItem, DownloadItem::class.java)
                    Gson().fromJson(json, DownloadItem::class.java)
                } else {
                    downloadViewModel.createDownloadItemFromResult(
                        resultItem,
                        url,
                        DownloadType.image
                    )
                }
            }
            if (!isAdded) return@launch

            bindUi()
        }
    }

    private fun bindUi() {
        val currentExt = downloadItem.container.lowercase(Locale.US)
            .takeIf { it in listOf("jpg", "png", "webp") } ?: "jpg"
        downloadItem.container = currentExt
        downloadItem.format.container = currentExt

        titleInput.editText?.setText(downloadItem.title)
        titleInput.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                downloadItem.title = s?.toString().orEmpty()
            }
        })

        saveDirInput.editText?.apply {
            setText(FileUtil.formatPath(downloadItem.downloadPath))
            isFocusable = false
            isClickable = true
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                pathResultLauncher.launch(intent)
            }
        }
        UiUtil.setupRecentDownloadPaths(saveDirInput) { setDownloadPath(it) }
        updateFreeSpace()

        AccessibilityUtil.setStateDescription(
            toggleAdvancedBtn,
            getString(if (advancedContainer.isVisible) R.string.state_expanded else R.string.state_collapsed)
        )
        toggleAdvancedBtn.setOnClickListener {
            val nextVisible = !advancedContainer.isVisible
            advancedContainer.isVisible = nextVisible
            toggleAdvancedBtn.setText(
                if (nextVisible) R.string.hide_advanced_options else R.string.advanced_options
            )
            AccessibilityUtil.setStateDescription(
                toggleAdvancedBtn,
                getString(if (nextVisible) R.string.state_expanded else R.string.state_collapsed)
            )
        }

        renderFormatCapsules(currentExt)
        loadPreviewImage()
    }

    private fun renderFormatCapsules(selectedExt: String) {
        formatCapsules.removeAllViews()
        val radiusPx = 20f * resources.displayMetrics.density

        QualityCapsuleUtil.IMAGE_FORMAT_CAPSULES.forEach { capsule ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = capsule.label
                contentDescription = getString(R.string.select_option_desc, capsule.label)
                isCheckable = true
                isCheckedIconVisible = false
                chipCornerRadius = radiusPx
                isChecked = capsule.extension == selectedExt
                setOnClickListener {
                    downloadItem.container = capsule.extension
                    downloadItem.format.container = capsule.extension
                    selectedSummary.text = capsule.label
                }
            }
            formatCapsules.addView(chip)
        }

        selectedSummary.text = selectedExt.uppercase(Locale.US)
    }

    private fun loadPreviewImage() {
        val previewUrl = downloadItem.format.url?.trim()?.takeIf {
            (ImageDownloadHelper.isLikelyImageUrl(it) || it.startsWith("data:image/", ignoreCase = true)) &&
                !BrowserMediaUtil.isGenericSiteImageUrl(it)
        } ?: downloadItem.thumb.trim().takeIf {
            (ImageDownloadHelper.isLikelyImageUrl(it) || it.startsWith("data:image/", ignoreCase = true)) &&
                !BrowserMediaUtil.isGenericSiteImageUrl(it)
        }

        if (!previewUrl.isNullOrBlank()) {
            showPreviewUrl(previewUrl)
            return
        }

        imagePreviewEmpty.isVisible = true
        val targetPage = downloadItem.url
        if (targetPage.startsWith("http")) {
            viewLifecycleOwner.lifecycleScope.launch {
                val ctx = context?.applicationContext ?: return@launch
                val candidates = ImageDownloadHelper.extractImageCandidatesFromPage(targetPage, ctx)
                val first = candidates.firstOrNull { !BrowserMediaUtil.isGenericSiteImageUrl(it) }
                if (!first.isNullOrBlank() && isAdded) {
                    downloadItem.thumb = first
                    downloadItem.format.url = first
                    showPreviewUrl(first)
                }
            }
        }
    }

    private fun showPreviewUrl(url: String) {
        imagePreviewEmpty.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context?.applicationContext ?: return@launch
            val bmp = ImageDownloadHelper.fetchPreviewBitmap(ctx, url, downloadItem.url)
            if (!isAdded) return@launch
            if (bmp != null) {
                imagePreview.setImageBitmap(bmp)
                imagePreviewEmpty.isVisible = false
            } else if (url.startsWith("http")) {
                Picasso.get()
                    .load(url)
                    .fit()
                    .centerCrop()
                    .into(imagePreview, object : Callback {
                        override fun onSuccess() {
                            if (isAdded) imagePreviewEmpty.isVisible = false
                        }

                        override fun onError(e: Exception?) {
                            if (isAdded) imagePreviewEmpty.isVisible = true
                        }
                    })
            } else {
                imagePreviewEmpty.isVisible = true
            }
        }
    }

    private var pathResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let {
                activity?.contentResolver?.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                setDownloadPath(it.toString())
            }
        }
    }

    private fun setDownloadPath(path: String) {
        downloadItem.downloadPath = path
        saveDirInput.editText?.setText(FileUtil.formatPath(path), TextView.BufferType.EDITABLE)
        updateFreeSpace()
    }

    private fun updateFreeSpace() {
        val free = FileUtil.convertFileSize(
            File(FileUtil.formatPath(downloadItem.downloadPath)).freeSpace
        )
        freeSpaceText.text = String.format(getString(R.string.freespace) + ": " + free)
        freeSpaceText.isVisible = free != "?"
    }

    fun getDownloadItemOrNull(): DownloadItem? =
        if (::downloadItem.isInitialized) downloadItem else null

    override fun updateTitleAuthor(t: String, a: String) {
        if (!::downloadItem.isInitialized) return
        downloadItem.title = t
        downloadItem.author = a
        if (::titleInput.isInitialized) {
            titleInput.editText?.setText(t)
        }
    }

    override fun updateUI(res: ResultItem?) {
        resultItem = res
        if (!isAdded || view == null) return
        val state = Bundle().apply { putBoolean("updated", true) }
        onViewCreated(requireView(), state)
    }
}
