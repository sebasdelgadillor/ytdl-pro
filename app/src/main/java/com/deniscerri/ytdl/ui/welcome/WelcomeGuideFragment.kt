package com.deniscerri.ytdl.ui.welcome

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.ui.more.ForkInfoDialog

class WelcomeGuideFragment : Fragment(R.layout.fragment_welcome_guide) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.open_guide).setOnClickListener {
            ForkInfoDialog.show(requireContext())
        }
    }
}
