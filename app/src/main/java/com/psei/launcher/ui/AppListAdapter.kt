package com.psei.launcher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.psei.launcher.data.AppModel
import com.psei.launcher.databinding.ItemAppBinding

/**
 * High-performance, text-only RecyclerView Adapter.
 * Uses stable IDs and ViewBinding with zero image decoding overhead.
 */
class AppListAdapter(
    private val onAppClick: (AppModel) -> Unit,
    private val onAppLongClick: ((AppModel) -> Unit)? = null
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    private var allApps: List<AppModel> = emptyList()
    private var displayedApps: List<AppModel> = emptyList()
    private var currentTextSizeSp: Float = 20f

    fun submitList(apps: List<AppModel>, currentQuery: String = "") {
        allApps = apps
        filter(currentQuery)
    }

    fun setTextSizeSp(sizeSp: Float) {
        if (currentTextSizeSp != sizeSp) {
            currentTextSizeSp = sizeSp
            notifyDataSetChanged()
        }
    }

    fun filter(query: String) {
        val trimmed = query.trim()
        val newList = if (trimmed.isEmpty()) {
            allApps
        } else {
            allApps.filter { app ->
                app.label.contains(trimmed, ignoreCase = true)
            }
        }
        displayedApps = newList
        notifyDataSetChanged()
    }

    fun getDisplayedApps(): List<AppModel> = displayedApps

    override fun getItemId(position: Int): Long {
        return displayedApps[position].stableId
    }

    override fun getItemCount(): Int = displayedApps.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(displayedApps[position])
    }

    inner class AppViewHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppModel) {
            binding.tvAppName.textSize = currentTextSizeSp
            binding.tvAppName.text = app.label

            binding.root.setOnClickListener {
                onAppClick(app)
            }

            if (onAppLongClick != null) {
                binding.root.setOnLongClickListener {
                    onAppLongClick.invoke(app)
                    true
                }
            } else {
                binding.root.setOnLongClickListener(null)
            }
        }
    }
}


