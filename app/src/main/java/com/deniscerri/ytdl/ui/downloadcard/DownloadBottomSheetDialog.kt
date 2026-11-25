package com.deniscerri.ytdl.ui.downloadcard

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.repository.DownloadRepository
import com.deniscerri.ytdl.database.viewmodel.CommandTemplateViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.FormatViewModel
import com.deniscerri.ytdl.database.viewmodel.HistoryViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.receiver.ShareActivity
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.ui.more.cookies.WebViewActivity
import com.deniscerri.ytdl.util.AccessibilityUtil
import com.deniscerri.ytdl.util.BrowserMediaUtil
import com.deniscerri.ytdl.util.UiUtil
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL


class DownloadBottomSheetDialog : BottomSheetDialogFragment() {
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager2: ViewPager2
    private lateinit var fragmentAdapter : DownloadFragmentAdapter
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var historyViewModel: HistoryViewModel
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private lateinit var formatViewModel: FormatViewModel
    private lateinit var behavior: BottomSheetBehavior<View>
    private lateinit var commandTemplateViewModel : CommandTemplateViewModel
    private lateinit var sharedPreferences : SharedPreferences
    private lateinit var updateItem : Button
    private lateinit var view: View
    private lateinit var shimmerLoading :ShimmerFrameLayout
    private lateinit var title : View
    private lateinit var shimmerLoadingSubtitle : ShimmerFrameLayout
    private lateinit var subtitle : View
    private lateinit var parentActivity: BaseActivity

    private lateinit var scheduleBtn: Button
    private lateinit var downloadBtn: Button


    private lateinit var result: ResultItem
    private lateinit var type: DownloadType
    private var ignoreDuplicates: Boolean = false
    private var disableUpdateData : Boolean = false
    private var currentDownloadItem: DownloadItem? = null
    private var incognito: Boolean = false
    private var hasTriggeredLazyFormats: Boolean = false
    private var forceUpdateData: Boolean = false
    private var includeCommandTab: Boolean = false
    private var allowDynamicTypeSwitch: Boolean = true
    private var isImageOnly: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        historyViewModel = ViewModelProvider(requireActivity())[HistoryViewModel::class.java]
        resultViewModel = ViewModelProvider(requireActivity())[ResultViewModel::class.java]
        commandTemplateViewModel = ViewModelProvider(requireActivity())[CommandTemplateViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(requireActivity())[DownloadCardViewModel::class.java]
        formatViewModel = ViewModelProvider(requireActivity())[FormatViewModel::class.java]
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val res = downloadCardViewModel.resultItem
        val dwl = downloadCardViewModel.downloadItem

        type = arguments?.getSerializable("type") as DownloadType
        disableUpdateData = arguments?.getBoolean("disableUpdateData") == true
        forceUpdateData = arguments?.getBoolean("force_update_data") == true
        ignoreDuplicates = arguments?.getBoolean("ignore_duplicates") == true
        allowDynamicTypeSwitch = arguments?.getBoolean("allow_dynamic_type_switch", true) ?: true

        if (res == null){
            dismiss()
            return
        }
        result = res
        currentDownloadItem = dwl
        incognito = currentDownloadItem?.incognito ?: sharedPreferences.getBoolean("incognito", false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val downloadItem = getDownloadItem()
        downloadCardViewModel.setResultItem(result)
        downloadCardViewModel.setDownloadItem(downloadItem)
        arguments?.putSerializable("type", downloadItem.type)
    }

    @SuppressLint("RestrictedApi", "InflateParams")
    override fun setupDialog(dialog: Dialog, style: Int) {
        super.setupDialog(dialog, style)
        view = LayoutInflater.from(context).inflate(R.layout.download_bottom_sheet, null)
        dialog.setContentView(view)
        dialog.window?.navigationBarColor = SurfaceColors.SURFACE_1.getColor(requireActivity())
        parentActivity = activity as BaseActivity

        dialog.setOnShowListener {
            behavior = BottomSheetBehavior.from(view.parent as View)
            val displayMetrics = DisplayMetrics()
            requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
            if(resources.getBoolean(R.bool.isTablet) || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE){
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.peekHeight = displayMetrics.heightPixels
            }
        }

        tabLayout = view.findViewById(R.id.download_tablayout)
        viewPager2 = view.findViewById(R.id.download_viewpager)
        updateItem = view.findViewById(R.id.update_item)
        viewPager2.isUserInputEnabled = sharedPreferences.getBoolean("swipe_gestures_download_card", true)


        //loading shimmers
        shimmerLoading = view.findViewById(R.id.shimmer_loading_title)
        title = view.findViewById(R.id.bottom_sheet_title)
        shimmerLoadingSubtitle = view.findViewById(R.id.shimmer_loading_subtitle)
        subtitle = view.findViewById(R.id.bottom_sheet_subtitle)

        fun cancelLoadingData() {
            lifecycleScope.launch {
                resultViewModel.cancelUpdateItemData()
                (updateItem.parent as LinearLayout).visibility = View.VISIBLE
            }
        }

        shimmerLoading.setOnClickListener {
            cancelLoadingData()
        }
        shimmerLoadingSubtitle.setOnClickListener {
            cancelLoadingData()
        }


        (viewPager2.getChildAt(0) as? RecyclerView)?.apply {
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val isCommandOnly = (type == DownloadType.command && !Patterns.WEB_URL.matcher(result.url).matches())
        includeCommandTab = isCommandOnly || type == DownloadType.command
        if (includeCommandTab) {
            tabLayout.addTab(tabLayout.newTab().setText(R.string.command))
        }

        //check if the item has formats and its audio-only or image-only
        isImageOnly = BrowserMediaUtil.isImageOnlyResult(result)
        var isAudioOnly = BrowserMediaUtil.isAudioOnlyResult(result)
        if (isAudioOnly){
            (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.isClickable = true
            (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.alpha = 0.3f
        }

        (title as? TextView)?.text = result.title.ifBlank { getString(R.string.download) }

        //remove outdated player url of 1hr so it can refetch it in the cut player
        if (result.creationTime > System.currentTimeMillis() - 3600000) result.urls = ""
        val fragmentManager = parentFragmentManager
        fragmentAdapter = DownloadFragmentAdapter(
            fragmentManager,
            lifecycle,
            result,
            currentDownloadItem,
            nonSpecific = result.url.endsWith(".txt"),
            isIncognito = incognito,
            useModernMediaTabs = true,
            includeCommandTab = includeCommandTab
        )

        viewPager2.adapter = fragmentAdapter
        viewPager2.isSaveFromParentEnabled = false

        view.post {
            when {
                type == DownloadType.command -> {
                    val cmdIdx = if (includeCommandTab) 3 else 0
                    tabLayout.getTabAt(cmdIdx)!!.select()
                    viewPager2.postDelayed({
                        viewPager2.setCurrentItem(cmdIdx, false)
                    }, 200)
                }
                isImageOnly || type == DownloadType.image -> {
                    tabLayout.getTabAt(2)!!.select()
                    viewPager2.setCurrentItem(2, false)
                }
                type == DownloadType.audio -> {
                    tabLayout.getTabAt(1)!!.select()
                    viewPager2.setCurrentItem(1, false)
                }
                isAudioOnly -> {
                    tabLayout.getTabAt(1)!!.select()
                    viewPager2.setCurrentItem(1, false)
                    Toast.makeText(context, getString(R.string.audio_only_item), Toast.LENGTH_SHORT).show()
                }
                else -> {
                    tabLayout.getTabAt(0)!!.select()
                    viewPager2.setCurrentItem(0, false)
                }
            }

            //check if the item is coming from a text file
            if (isCommandOnly){
                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.isClickable = false
                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.alpha = 0.3f

                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.isClickable = false
                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.alpha = 0.3f

                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(2)?.isClickable = false
                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(2)?.alpha = 0.3f

                (updateItem.parent as LinearLayout).visibility = View.GONE
            }
        }

        sharedPreferences.edit(commit = true) {
            putString("last_used_download_type",
                type.toString())
        }


        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab == null) return
                if (tab.position == 0 && isAudioOnly){
                    tabLayout.selectTab(tabLayout.getTabAt(1))
                    Toast.makeText(context, getString(R.string.audio_only_item), Toast.LENGTH_SHORT).show()
                } else {
                    viewPager2.setCurrentItem(tab.position, false)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {
            }

            override fun onTabReselected(tab: TabLayout.Tab?) {
            }
        })

        viewPager2.registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                tabLayout.selectTab(tabLayout.getTabAt(position))
                runCatching {
                    val selectedTabType = tabTypeForPosition(position)
                    sharedPreferences.edit(commit = true) {
                        putString(
                            "last_used_download_type",
                            selectedTabType.toString()
                        )
                    }
                    fragmentAdapter.updateWhenSwitching(viewPager2.currentItem)

                    if ((selectedTabType == DownloadType.video || selectedTabType == DownloadType.audio) &&
                        !disableUpdateData &&
                        !isImageOnly &&
                        !hasTriggeredLazyFormats &&
                        result.url.startsWith("http") &&
                        !BrowserMediaUtil.isDirectStaticImageFile(result.url)
                    ) {
                        if ((forceUpdateData || result.title.isEmpty()) &&
                            currentDownloadItem == null &&
                            !sharedPreferences.getBoolean("quick_download", false)
                        ) {
                            hasTriggeredLazyFormats = true
                            initUpdateData()
                        } else {
                            val needsFormats = result.formats.isEmpty() ||
                                result.formats.any {
                                    it.format_note.contains("ytdlnisgeneric") ||
                                        it.format_id.startsWith("fb_")
                                }
                            if (needsFormats && !sharedPreferences.getBoolean("quick_download", false)) {
                                hasTriggeredLazyFormats = true
                                initUpdateFormats(result)
                            }
                        }
                    }
                }
            }
        })

        viewPager2.setPageTransformer(BackgroundToForegroundPageTransformer())

        val shownFields = sharedPreferences.getStringSet("modify_download_card", requireContext().resources.getStringArray(R.array.modify_download_card_values).toSet())!!.toList()

        scheduleBtn = view.findViewById<MaterialButton>(R.id.bottomsheet_schedule_button)
        scheduleBtn.visibility = if(shownFields.contains("schedule")){
            View.VISIBLE
        }else{
            View.GONE
        }
        downloadBtn = view.findViewById<Button>(R.id.bottomsheet_download_button)


        scheduleBtn.setOnClickListener{
            UiUtil.showDatePicker(fragmentManager, sharedPreferences) {
                lifecycleScope.launch {
                    resultViewModel.cancelUpdateItemData()
                    formatViewModel.cancelUpdateFormats()
                }

                scheduleBtn.isEnabled = false
                downloadBtn.isEnabled = false
                val item: DownloadItem = getDownloadItem()
                item.status = DownloadRepository.Status.Scheduled.toString()
                item.downloadStartTime = it.timeInMillis
                if (item.videoPreferences.alsoDownloadAsAudio){
                    val itemsToQueue = mutableListOf<DownloadItem>()
                    itemsToQueue.add(item)

                    getAlsoAudioDownloadItem(finished = { audioDownloadItem ->
                        audioDownloadItem.downloadStartTime = it.timeInMillis
                        audioDownloadItem.status = DownloadRepository.Status.Scheduled.toString()
                        itemsToQueue.add(audioDownloadItem)

                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO){
                                downloadViewModel.queueDownloads(itemsToQueue, ignoreDuplicates)
                            }

                            if (result.message.isNotBlank()){
                                Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                            }

                            withContext(Dispatchers.Main){
                                handleDuplicatesAndDismiss(result.duplicateDownloadIDs)
                            }
                        }
                    })
                }else{
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO){
                            downloadViewModel.queueDownloads(listOf(item), ignoreDuplicates)
                        }

                        if (result.message.isNotBlank()){
                            Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                        }

                        withContext(Dispatchers.Main){
                            handleDuplicatesAndDismiss(result.duplicateDownloadIDs)
                        }
                    }
                }

            }
        }
        downloadBtn.setOnClickListener {
            lifecycleScope.launch {
                resultViewModel.cancelUpdateItemData()
                formatViewModel.cancelUpdateFormats()
                scheduleBtn.isEnabled = false
                downloadBtn.isEnabled = false
                val item: DownloadItem = getDownloadItem()
                if (item.videoPreferences.alsoDownloadAsAudio){
                    val itemsToQueue = mutableListOf<DownloadItem>()
                    itemsToQueue.add(item)

                    getAlsoAudioDownloadItem(finished = {
                        itemsToQueue.add(it)

                        lifecycleScope.launch {
                            val queueRes = withContext(Dispatchers.IO) {
                                downloadViewModel.queueDownloads(itemsToQueue, ignoreDuplicates)
                            }
                            if (queueRes.message.isNotBlank()) {
                                Toast.makeText(requireContext(), queueRes.message, Toast.LENGTH_LONG).show()
                            } else if (queueRes.duplicateDownloadIDs.isEmpty() && activity !is ShareActivity) {
                                Toast.makeText(requireContext(), R.string.download_started_toast, Toast.LENGTH_SHORT).show()
                            }
                            withContext(Dispatchers.Main){
                                handleDuplicatesAndDismiss(queueRes.duplicateDownloadIDs)
                            }
                        }
                    })
                }else{
                    val queueRes = withContext(Dispatchers.IO) {
                        downloadViewModel.queueDownloads(listOf(item), ignoreDuplicates)
                    }
                    if (queueRes.message.isNotBlank()) {
                        Toast.makeText(requireContext(), queueRes.message, Toast.LENGTH_LONG).show()
                    } else if (queueRes.duplicateDownloadIDs.isEmpty() && activity !is ShareActivity) {
                        Toast.makeText(requireContext(), R.string.download_started_toast, Toast.LENGTH_SHORT).show()
                    }
                    handleDuplicatesAndDismiss(queueRes.duplicateDownloadIDs)
                }
            }
        }

        downloadBtn.setOnLongClickListener {
            val dd = MaterialAlertDialogBuilder(requireContext())
            dd.setTitle(getString(R.string.save_for_later))
            dd.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
            dd.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
                lifecycleScope.launch(Dispatchers.IO){
                    downloadViewModel.putToSaved(getDownloadItem())
                    dismiss()
                }
            }
            dd.show()
            true
        }

        val link = view.findViewById<Button>(R.id.bottom_sheet_link)
        link.visibility = if(shownFields.contains("url")){
            View.VISIBLE
        }else{
            View.GONE
        }

        if (Patterns.WEB_URL.matcher(result.url).matches()){
            link.text = result.url
            link.setOnClickListener{
                UiUtil.openLinkIntent(requireContext(), result.url)
            }
            link.setOnLongClickListener{
                UiUtil.copyLinkToClipBoard(requireContext(), result.url)
                true
            }

            //if auto-update after the card is open is off
            if (result.title.isEmpty() && currentDownloadItem == null && sharedPreferences.getBoolean("quick_download", false)) {
                (updateItem.parent as LinearLayout).visibility = View.VISIBLE
                updateItem.setOnClickListener {
                    (updateItem.parent as LinearLayout).visibility = View.GONE
                    initUpdateData()
                }
            }else{
                (updateItem.parent as LinearLayout).visibility = View.GONE
            }

        }else{
            link.visibility = View.GONE
            (updateItem.parent as LinearLayout).visibility = View.GONE
        }

        val incognitoBtn = view.findViewById<Button>(R.id.bottomsheet_incognito)
        incognitoBtn.alpha = if (incognito) 1f else 0.3f
        AccessibilityUtil.setStateDescription(
            incognitoBtn,
            getString(if (incognito) R.string.state_enabled else R.string.state_disabled)
        )
        incognitoBtn.setOnClickListener {
            if (incognito) {
                it.alpha = 0.3f
            }else{
                it.alpha = 1f
            }

            incognito = !incognito
            fragmentAdapter.isIncognito = incognito
            AccessibilityUtil.setStateDescription(
                incognitoBtn,
                getString(if (incognito) R.string.state_enabled else R.string.state_disabled)
            )
            val onOff = if (incognito) getString(R.string.ok) else getString(R.string.disabled)
            Snackbar.make(incognitoBtn, "${getString(R.string.incognito)}: $onOff", Snackbar.LENGTH_SHORT).show()
        }


        //update in the background if there is no data
        val hasDirectImagePreloaded = type == DownloadType.image && (
            result.thumb.startsWith("http") ||
                result.thumb.startsWith("data:image/", ignoreCase = true) ||
                com.deniscerri.ytdl.util.BrowserMediaUtil.isDirectStaticImageFile(result.url)
            )
        if (!disableUpdateData && !hasDirectImagePreloaded) {
            if((forceUpdateData || result.title.isEmpty()) && currentDownloadItem == null && !sharedPreferences.getBoolean("quick_download", false) && type != DownloadType.command){
                hasTriggeredLazyFormats = true
                initUpdateData()
            }else {
                val usingGenericFormatsOrEmpty = !isImageOnly && (
                    result.formats.isEmpty() || result.formats.any {
                        it.format_note.contains("ytdlnisgeneric") || it.format_id.startsWith("fb_")
                    }
                )
                if (usingGenericFormatsOrEmpty && !sharedPreferences.getBoolean("quick_download", false) && type != DownloadType.command){
                    hasTriggeredLazyFormats = true
                    initUpdateFormats(result)
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.uiState.collectLatest { res ->
                if (res.errorMessage != null){
                    val msg = res.errorMessage!!
                    kotlin.runCatching {
                        shimmerLoading.stopShimmer()
                        shimmerLoadingSubtitle.stopShimmer()
                        shimmerLoading.visibility = View.GONE
                        shimmerLoadingSubtitle.visibility = View.GONE
                        title.visibility = View.VISIBLE
                        subtitle.visibility = View.VISIBLE
                        downloadBtn.isEnabled = true
                        scheduleBtn.isEnabled = true

                        if (result.url.startsWith("http")) {
                            (updateItem.parent as LinearLayout).visibility = View.VISIBLE
                            updateItem.setOnClickListener {
                                (updateItem.parent as LinearLayout).visibility = View.GONE
                                initUpdateData()
                            }
                        }

                        (tabLayout.getChildAt(0) as? ViewGroup)?.let { strip ->
                            for (i in 0 until minOf(3, strip.childCount)) {
                                strip.getChildAt(i)?.apply {
                                    isClickable = true
                                    alpha = 1f
                                }
                            }
                        }

                        if (allowDynamicTypeSwitch &&
                            (BrowserMediaUtil.isLikelyPhotoPageUrl(result.url) || BrowserMediaUtil.isDirectStaticImageFile(result.url))
                        ) {
                            tabLayout.getTabAt(2)?.select()
                            viewPager2.setCurrentItem(2, false)
                        }

                        val cookieRelated = msg.contains("cookie", ignoreCase = true) ||
                            msg.contains("sign in", ignoreCase = true) ||
                            msg.contains("login", ignoreCase = true)
                        val sb = Snackbar.make(view, getString(R.string.media_type_uncertain_choose_format), Snackbar.LENGTH_LONG)
                        if (cookieRelated && result.url.startsWith("http")) {
                            sb.setAction(R.string.get_cookies) {
                                val targetUrl = "https://${URL(result.url).host}"
                                val mainAct = activity as? MainActivity
                                if (mainAct != null) {
                                    dismiss()
                                    mainAct.openBrowserWithUrl(targetUrl)
                                } else {
                                    val myIntent = Intent(requireContext(), WebViewActivity::class.java)
                                    myIntent.putExtra("url", targetUrl)
                                    cookiesFetchedResultLauncher.launch(myIntent)
                                }
                            }
                        }
                        sb.show()
                    }

                    resultViewModel.uiState.update { it.copy(errorMessage = null) }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updatingData.collectLatest {
                kotlin.runCatching {
                    if (it){
                        title.visibility = View.GONE
                        subtitle.visibility = View.GONE
                        shimmerLoading.visibility = View.VISIBLE
                        shimmerLoadingSubtitle.visibility = View.VISIBLE
                        shimmerLoading.startShimmer()
                        shimmerLoadingSubtitle.startShimmer()
                        (updateItem.parent as LinearLayout).visibility = View.GONE
                        downloadBtn.isEnabled = false
                        scheduleBtn.isEnabled = false
                    }else{
                        title.visibility = View.VISIBLE
                        subtitle.visibility = View.VISIBLE
                        shimmerLoading.visibility = View.GONE
                        shimmerLoadingSubtitle.visibility = View.GONE
                        shimmerLoading.stopShimmer()
                        shimmerLoadingSubtitle.stopShimmer()
                        downloadBtn.isEnabled = true
                        scheduleBtn.isEnabled = true
                    }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updateResultData.collectLatest { result ->
                if (result == null) return@collectLatest
                kotlin.runCatching {
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (result.size == 1 && result[0] != null) {
                            val previousThumb = this@DownloadBottomSheetDialog.result.thumb
                            val rawRes = result[0]!!
                            val res = if (rawRes.thumb.isBlank() && previousThumb.isNotBlank()) {
                                rawRes.copy(thumb = previousThumb)
                            } else {
                                rawRes
                            }
                            this@DownloadBottomSheetDialog.result = res
                            (title as? TextView)?.text = res.title.ifBlank { getString(R.string.download) }
                            fragmentAdapter.setResultItem(res)

                            isImageOnly = BrowserMediaUtil.isImageOnlyResult(res)
                            if (isImageOnly) {
                                isAudioOnly = false
                                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.apply {
                                    isClickable = true
                                    alpha = 1f
                                }
                                if (allowDynamicTypeSwitch && type != DownloadType.command) {
                                    tabLayout.getTabAt(2)?.select()
                                    viewPager2.setCurrentItem(2, false)
                                }
                            } else {
                                isAudioOnly = BrowserMediaUtil.isAudioOnlyResult(res)
                                if (res.formats.isNotEmpty()) {
                                    (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.apply {
                                        isClickable = true
                                        alpha = if (isAudioOnly) 0.3f else 1f
                                    }
                                    if (allowDynamicTypeSwitch && type != DownloadType.command) {
                                        if (isAudioOnly) {
                                            tabLayout.getTabAt(1)?.select()
                                            viewPager2.setCurrentItem(1, false)
                                        } else if (tabLayout.selectedTabPosition == 2 &&
                                            !BrowserMediaUtil.isDirectStaticImageFile(res.url)
                                        ) {
                                            tabLayout.getTabAt(0)?.select()
                                            viewPager2.setCurrentItem(0, false)
                                        }
                                    }
                                }
                            }

                            title.visibility = View.VISIBLE
                            subtitle.visibility = View.VISIBLE
                            shimmerLoading.visibility = View.GONE
                            shimmerLoadingSubtitle.visibility = View.GONE
                            shimmerLoading.stopShimmer()
                            shimmerLoadingSubtitle.stopShimmer()

                            val usingGenericFormatsOrEmpty = !isImageOnly && (
                                res.formats.isEmpty() || res.formats.any { it.format_note.contains("ytdlnisgeneric") }
                                )
                            downloadCardViewModel.setResultItem(res)
                            if (usingGenericFormatsOrEmpty && !sharedPreferences.getBoolean("quick_download", false)){
                                initUpdateFormats(res)
                            }

                        }else if (result.size > 1) {
                            //open multi download card instead
                            val ids = result.mapNotNull { it?.id }.filter { it > 0L }
                            if (activity is ShareActivity){
                                findNavController().navigate(R.id.action_downloadBottomSheetDialog_to_selectPlaylistItemsDialog, bundleOf(
                                    Pair("resultIDs", ids.toLongArray()),
                                ))
                            }else if (ids.isNotEmpty()){
                                val navController = runCatching { findNavController() }.getOrNull()
                                downloadViewModel.turnResultItemsToProcessingDownloads(ids, downloadNow = false).join()
                                dismiss()
                                runCatching {
                                    navController?.navigate(R.id.downloadMultipleBottomSheetDialog2)
                                }
                            }else{
                                dismiss()
                            }
                        }

                        resultViewModel.updateResultData.emit(null)
                    }

                }
            }
        }

        lifecycleScope.launch {
            launch{
                downloadViewModel.alreadyExistsUiState.collectLatest { res ->
                    if (res.isNotEmpty() && activity is ShareActivity){
                        withContext(Dispatchers.Main){
                            val bundle = bundleOf(
                                Pair("duplicates", ArrayList(res))
                            )
                            delay(500)
                            findNavController().navigate(R.id.action_downloadBottomSheetDialog_to_downloadsAlreadyExistDialog2, bundle)
                        }
                        downloadViewModel.alreadyExistsUiState.value = mutableListOf()
                    }
                }
            }
        }

        lifecycleScope.launch {
            formatViewModel.formatUpdates.collect { event ->
                kotlin.runCatching {
                    when (event) {
                        is FormatViewModel.FormatUpdateEvent.Updated -> {
                            if (event.url != result.url) return@runCatching
                            val formats = event.formats
                            isAudioOnly = formats.isNotEmpty() && formats.none { !it.format_note.contains("audio") }
                            if (isAudioOnly){
                                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.isClickable = true
                                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.alpha = 0.3f
                                Toast.makeText(context, getString(R.string.audio_only_item), Toast.LENGTH_SHORT).show()
                                tabLayout.getTabAt(1)!!.select()
                                viewPager2.setCurrentItem(1, false)
                            }
                            if (formats.isNotEmpty()){
                                result.formats = formats
                            }
                        }
                        is FormatViewModel.FormatUpdateEvent.Error -> {
                            // an open format sheet reports it itself; skip errors on image-only items
                            if (isImageOnly || parentFragmentManager.findFragmentByTag("formatSheet") != null) return@runCatching
                            resultViewModel.uiState.update { it.copy(processing = false, errorMessage = event.message) }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private var cookiesFetchedResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            sharedPreferences.edit().putBoolean("use_cookies", true).apply()
            updateItem.isVisible = true
            initUpdateData()
        }
    }

    private fun tabTypeForPosition(position: Int): DownloadType {
        val tabTypes = listOfNotNull(
            DownloadType.video,
            DownloadType.audio,
            DownloadType.image,
            if (includeCommandTab) DownloadType.command else null
        )
        return tabTypes.getOrElse(position) { type }
    }

    private fun getDownloadItem(selectedTabPosition: Int = tabLayout.selectedTabPosition) : DownloadItem {
        return fragmentAdapter.getDownloadItemOrNull(selectedTabPosition)
            ?: downloadViewModel.createDownloadItemFromResult(
                result,
                givenType = tabTypeForPosition(selectedTabPosition)
            ).apply {
                incognito = this@DownloadBottomSheetDialog.incognito
            }
    }

    private fun getAlsoAudioDownloadItem(finished: (it: DownloadItem) -> Unit) {
        val audioFragment = fragmentAdapter.getAudioFragment()
        val videoItem = fragmentAdapter.getVideoFragment()?.downloadItem
        val firstAudioId = videoItem?.videoPreferences?.audioFormatIDs?.firstOrNull()
        try {
            val ff = audioFragment!!
            if (!firstAudioId.isNullOrBlank()) {
                ff.updateSelectedAudioFormat(firstAudioId)
            }
            finished(ff.downloadItem)
        }catch (e: Exception){
            val fragmentLifecycleCallback = object:
                FragmentManager.FragmentLifecycleCallbacks() {

                override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                    if (f !is DownloadAudioFragment) return
                    fragmentManager?.unregisterFragmentLifecycleCallbacks(this)
                    f.requireView().post {
                        if (!firstAudioId.isNullOrBlank()) {
                            f.updateSelectedAudioFormat(firstAudioId)
                        }
                        finished(f.downloadItem)
                    }
                    super.onFragmentStarted(fm, f)
                }


            }

            fragmentManager?.registerFragmentLifecycleCallbacks(fragmentLifecycleCallback, true)
            viewPager2.setCurrentItem(1, true)
        }
    }

    private fun initUpdateData() {
        kotlin.runCatching {
            if (result.url.isBlank()) {
                dismiss()
                return
            }
            if (resultViewModel.updatingData.value) return

            lifecycleScope.launch(Dispatchers.IO) {
                resultViewModel.updateItemData(result)
            }
        }
    }

    private fun initUpdateFormats(res: ResultItem){
        formatViewModel.updateFormats(listOf(res.url))
    }

    override fun onDismiss(dialog: DialogInterface) {
        lifecycleScope.launch {
            resultViewModel.cancelUpdateItemData()
            formatViewModel.cancelUpdateFormats()
            super.onDismiss(dialog)
        }
    }

    private fun handleDuplicatesAndDismiss(res: List<DownloadViewModel.AlreadyExistsIDs>) {
        if (activity is ShareActivity && res.isNotEmpty()) {
            //let the lifecycle listener handle it
        }else{
            dismiss()
        }
    }
}

