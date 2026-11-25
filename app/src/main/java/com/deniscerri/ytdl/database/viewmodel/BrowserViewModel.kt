package com.deniscerri.ytdl.database.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BrowserViewModel : ViewModel() {
    private val _currentUrl = MutableStateFlow<String?>(null)
    val currentUrl: StateFlow<String?> = _currentUrl.asStateFlow()

    private val _pendingUrl = MutableStateFlow<String?>(null)
    val pendingUrl: StateFlow<String?> = _pendingUrl.asStateFlow()

    private val _desktopMode = MutableStateFlow(false)
    val desktopMode: StateFlow<Boolean> = _desktopMode.asStateFlow()

    private val _showAppNavBar = MutableStateFlow(false)
    val showAppNavBar: StateFlow<Boolean> = _showAppNavBar.asStateFlow()

    fun setCurrentUrl(url: String?) {
        _currentUrl.value = url
    }

    fun requestUrl(url: String) {
        _pendingUrl.value = url
    }

    fun consumePendingUrl(): String? {
        val url = _pendingUrl.value
        _pendingUrl.value = null
        return url
    }

    fun setDesktopMode(enabled: Boolean) {
        _desktopMode.value = enabled
    }

    fun setShowAppNavBar(visible: Boolean) {
        _showAppNavBar.value = visible
    }
}
