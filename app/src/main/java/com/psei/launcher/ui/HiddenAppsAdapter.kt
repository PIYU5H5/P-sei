package com.psei.launcher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.psei.launcher.data.AppModel
import com.psei.launcher.databinding.ItemHiddenAppBinding

/**
 * Adapter for managing hidden apps list.
 */
class HiddenAppsAdapter(
    private val onUnhideClick: (AppModel) -> Unit
) : RecyclerView.Adapter<HiddenAppsAdapter.HiddenAppViewHolder>() {

    private var items: List<AppModel> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun submitList(list: List<AppModel>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].stableId

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HiddenAppViewHolder {
        val binding = ItemHiddenAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HiddenAppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HiddenAppViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class HiddenAppViewHolder(private val binding: ItemHiddenAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppModel) {
            binding.tvHiddenAppName.text = app.label
            binding.btnUnhide.setOnClickListener {
                onUnhideClick(app)
            }
        }
    }
}


