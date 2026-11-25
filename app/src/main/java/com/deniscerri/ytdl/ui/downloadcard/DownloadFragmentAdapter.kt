package com.deniscerri.ytdl.ui.downloadcard

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.ResultItem

class DownloadFragmentAdapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle,
    private var result: ResultItem?,
    private var downloadItem: DownloadItem?,
    nonSpecific: Boolean = false,
    var isIncognito: Boolean = false,
    private val useModernMediaTabs: Boolean = false,
    private val includeCommandTab: Boolean = false
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    val fragments: List<Fragment> = if (useModernMediaTabs) {
        buildList {
            add(DownloadVideoFragment(result, downloadItem, "", nonSpecific))
            add(DownloadAudioFragment(result, downloadItem, "", nonSpecific))
            add(DownloadImageFragment(result, downloadItem, ""))
            if (includeCommandTab) {
                add(DownloadCommandFragment(result, downloadItem))
            }
        }
    } else {
        listOf(
            DownloadAudioFragment(result, downloadItem, "", nonSpecific),
            DownloadVideoFragment(result, downloadItem, "", nonSpecific),
            DownloadCommandFragment(result, downloadItem)
        )
    }

    fun getAudioFragment(): DownloadAudioFragment? =
        fragments.filterIsInstance<DownloadAudioFragment>().firstOrNull()

    fun getVideoFragment(): DownloadVideoFragment? =
        fragments.filterIsInstance<DownloadVideoFragment>().firstOrNull()

    fun setResultItem(res: ResultItem) {
        result = res
        fragments.forEach { frag ->
            runCatching {
                when (frag) {
                    is DownloadAudioFragment -> frag.updateUI(res)
                    is DownloadVideoFragment -> frag.updateUI(res)
                    is DownloadImageFragment -> frag.updateUI(res)
                    is DownloadCommandFragment -> frag.updateUI(res)
                }
            }
        }
    }

    private fun setTitleAuthor(t: String, a: String) {
        result?.title = t
        result?.author = a
        downloadItem?.title = t
        downloadItem?.author = a
    }

    override fun getItemCount(): Int = fragments.size

    override fun createFragment(position: Int): Fragment = fragments[position]

    fun updateWhenSwitching(currentViewPagerItem: Int) {
        val currentFrag = fragments.getOrNull(currentViewPagerItem) ?: return

        val prevPair = fragments.firstNotNullOfOrNull { frag ->
            if (frag === currentFrag) return@firstNotNullOfOrNull null
            runCatching {
                when (frag) {
                    is DownloadAudioFragment -> frag.downloadItem.title to frag.downloadItem.author
                    is DownloadVideoFragment -> frag.downloadItem.title to frag.downloadItem.author
                    is DownloadImageFragment -> frag.downloadItem.title to frag.downloadItem.author
                    is DownloadCommandFragment -> frag.downloadItem.title to frag.downloadItem.author
                    else -> null
                }
            }.getOrNull()
        }

        if (prevPair != null) {
            setTitleAuthor(prevPair.first, prevPair.second)
        }

        when (currentFrag) {
            is DownloadAudioFragment -> runCatching {
                if (prevPair != null) {
                    currentFrag.updateTitleAuthor(prevPair.first, prevPair.second)
                }
                val videoItem = getVideoFragment()?.downloadItem
                val audioId = videoItem?.videoPreferences?.audioFormatIDs?.firstOrNull()
                if (!audioId.isNullOrBlank()) {
                    currentFrag.updateSelectedAudioFormat(audioId)
                }
            }
            is DownloadVideoFragment -> runCatching {
                if (prevPair != null) {
                    currentFrag.updateTitleAuthor(prevPair.first, prevPair.second)
                }
                getAudioFragment()?.downloadItem?.format?.let { audioFormat ->
                    currentFrag.updateSelectedAudioFormat(audioFormat)
                }
            }
            is DownloadImageFragment -> runCatching {
                if (prevPair != null) {
                    currentFrag.updateTitleAuthor(prevPair.first, prevPair.second)
                }
            }
            is DownloadCommandFragment -> runCatching {
                if (prevPair != null) {
                    currentFrag.updateTitleAuthor(prevPair.first, prevPair.second)
                }
            }
        }
    }

    fun getDownloadItemOrNull(position: Int): DownloadItem? {
        val frag = fragments.getOrNull(position)
        val item = when (frag) {
            is DownloadAudioFragment -> frag.getDownloadItemOrNull()
            is DownloadVideoFragment -> frag.getDownloadItemOrNull()
            is DownloadImageFragment -> frag.getDownloadItemOrNull()
            is DownloadCommandFragment -> frag.getDownloadItemOrNull()
            else -> null
        } ?: downloadItem
        return item?.apply {
            incognito = isIncognito
        }
    }

    fun getDownloadItem(position: Int): DownloadItem {
        return getDownloadItemOrNull(position) ?: downloadItem!!
    }
}
