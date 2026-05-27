package com.dh.skipper

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private lateinit var enabledAppsSet: MutableSet<String>
    private var allApps = listOf<AppInfo>()
    private val PREFS_NAME = "skipper_config"
    private val KEY_ENABLED_APPS = "enabled_apps"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化内存中的选中状态
        enabledAppsSet = getEnabledApps()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btnToggle = findViewById<Button>(R.id.btnToggle)
        val rvApps = findViewById<RecyclerView>(R.id.rvApps)
        val etSearch = findViewById<EditText>(R.id.etSearch)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        btnToggle.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        etSearch.addTextChangedListener { text ->
            filterApps(text?.toString() ?: "")
        }

        rvApps.layoutManager = LinearLayoutManager(this)
        adapter = AppAdapter(enabledAppsSet) { pkgName, isChecked ->
            if (isChecked) enabledAppsSet.add(pkgName) else enabledAppsSet.remove(pkgName)
            saveAppStatus()
        }
        rvApps.adapter = adapter

        loadInstalledApps()
    }

    private fun filterApps(query: String) {
        val filtered = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        }
        adapter.submitList(filtered)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val enabled = isAccessibilityServiceEnabled()
        tvStatus.text = if (enabled) "服务已开启" else "服务未开启"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${AdSkipService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(serviceName, ignoreCase = true) }
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        
        // 使用 queryIntentActivities 代替 getInstalledApplications
        // 这样获取的是有桌面图标的应用，通常更符合用户勾选“跳过广告”的需求
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val appList = mutableListOf<AppInfo>()
        val seenPackages = mutableSetOf<String>()

        for (resolveInfo in resolveInfos) {
            val app = resolveInfo.activityInfo.applicationInfo
            if (app.packageName == packageName) continue
            if (seenPackages.contains(app.packageName)) continue
            
            seenPackages.add(app.packageName)
            appList.add(
                AppInfo(
                    app.loadLabel(pm).toString(),
                    app.packageName,
                    app.loadIcon(pm)
                )
            )
        }

        allApps = appList.sortedBy { it.name }
        adapter.submitList(allApps)
    }

    private fun getEnabledApps(): MutableSet<String> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_ENABLED_APPS, null)
        return set?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveAppStatus() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 必须创建一个新 Set 提交给 SharedPreferences，否则它可能不会检测到内容变化
        prefs.edit().putStringSet(KEY_ENABLED_APPS, HashSet(enabledAppsSet)).apply()
    }

    data class AppInfo(val name: String, val packageName: String, val icon: Drawable)

    class AppAdapter(
        private val enabledApps: MutableSet<String>,
        private val onStatusChanged: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        private var appList = listOf<AppInfo>()

        fun submitList(list: List<AppInfo>) {
            appList = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = appList[position]
            holder.ivIcon.setImageDrawable(app.icon)
            holder.tvName.text = app.name
            holder.cbEnabled.setOnCheckedChangeListener(null)
            holder.cbEnabled.isChecked = enabledApps.contains(app.packageName)
            holder.cbEnabled.setOnCheckedChangeListener { _, isChecked ->
                onStatusChanged(app.packageName, isChecked)
            }
        }

        override fun getItemCount() = appList.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
            val tvName: TextView = view.findViewById(R.id.tvAppName)
            val cbEnabled: CheckBox = view.findViewById(R.id.cbEnabled)
        }
    }
}
