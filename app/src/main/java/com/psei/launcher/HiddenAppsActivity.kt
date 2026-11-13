package com.psei.launcher

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.psei.launcher.data.AppModel
import com.psei.launcher.data.AppRepository
import com.psei.launcher.data.Prefs
import com.psei.launcher.databinding.ActivityHiddenAppsBinding
import com.psei.launcher.ui.HiddenAppsAdapter
import com.psei.launcher.ui.InsetsUtils

/**
 * Activity for viewing and restoring hidden apps.
 */
class HiddenAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHiddenAppsBinding
    private lateinit var prefs: Prefs
    private lateinit var appRepo: AppRepository
    private lateinit var adapter: HiddenAppsAdapter

    private fun applyCustomTheme() {
        when (prefs.theme) {
            Prefs.THEME_SEPIA -> setTheme(R.style.Theme_MinimalLauncher_Sepia)
            Prefs.THEME_FOREST -> setTheme(R.style.Theme_MinimalLauncher_Forest)
            Prefs.THEME_CHARCOAL -> setTheme(R.style.Theme_MinimalLauncher_Charcoal)
            Prefs.THEME_CYBER -> setTheme(R.style.Theme_MinimalLauncher_Cyber)
            Prefs.THEME_LIGHT -> setTheme(R.style.Theme_MinimalLauncher_Light)
            else -> setTheme(R.style.Theme_MinimalLauncher_Dark)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs.getInstance(this)
        applyCustomTheme()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        appRepo = AppRepository.getInstance(this)

        binding = ActivityHiddenAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInsets()
        setupRecyclerView()

        binding.btnBackHidden.setOnClickListener {
            finish()
        }

        loadHiddenApps()
    }

    private fun setupInsets() {
        InsetsUtils.applySystemWindowInsets(
            topView = binding.hiddenHeader,
            bottomView = binding.rvHiddenApps
        )
    }

    private fun setupRecyclerView() {
        adapter = HiddenAppsAdapter { appToUnhide ->
            prefs.setAppHidden(appToUnhide.componentKey, false)
            appRepo.reloadAppsAsync()
            loadHiddenApps()
        }

        binding.rvHiddenApps.apply {
            layoutManager = LinearLayoutManager(this@HiddenAppsActivity)
            adapter = this@HiddenAppsActivity.adapter
            setHasFixedSize(true)
        }
    }

    private fun loadHiddenApps() {
        val hiddenKeys = prefs.hiddenPackages
        val allApps = appRepo.getCachedApps()
        val hiddenList = allApps.filter { hiddenKeys.contains(it.componentKey) }

        adapter.submitList(hiddenList)
        binding.tvEmptyHidden.isVisible = hiddenList.isEmpty()
    }
}


