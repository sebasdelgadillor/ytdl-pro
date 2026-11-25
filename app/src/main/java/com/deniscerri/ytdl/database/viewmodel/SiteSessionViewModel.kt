package com.deniscerri.ytdl.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.models.SiteSession
import com.deniscerri.ytdl.util.SiteSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SiteSessionViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = DBManager.getInstance(application).siteSessionDao

    val allSessions: Flow<List<SiteSession>> = dao.getAllFlow()
    val loggedInSessions: Flow<List<SiteSession>> = dao.getLoggedInFlow()

    suspend fun getByDomain(domain: String): SiteSession? = withContext(Dispatchers.IO) {
        dao.getByDomain(domain)
    }

    suspend fun refreshSession(domain: String): SiteSession? = withContext(Dispatchers.IO) {
        SiteSessionManager.refreshSession(getApplication(), domain)
    }

    suspend fun removeSession(domain: String, clearBrowserCookies: Boolean = true) =
        withContext(Dispatchers.IO) {
            SiteSessionManager.removeSession(getApplication(), domain, clearBrowserCookies)
        }
}
