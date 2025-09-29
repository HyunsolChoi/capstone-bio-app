package com.jjangdol.biorhythm.ui.weather

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jjangdol.biorhythm.R

class HourAdapter : RecyclerView.Adapter<HourAdapter.VH>() {

    data class HourUI(
        val time: String,   // "14:00"
        val temp: String,   // "26°"
        val iconRes: Int,   // R.drawable.ic_weather_sunny
        val extra: String   // "강수확률 10%" 등
    )

    private val items = mutableListOf<HourUI>()

    fun submit(list: List<HourUI>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime: TextView = v.findViewById(R.id.tvTime)
        val ivIcon: ImageView = v.findViewById(R.id.ivIcon)
        val tvTemp: TextView = v.findViewById(R.id.tvTemp)
        val tvEtc: TextView  = v.findViewById(R.id.tvEtc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_hour_forecast, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvTime.text = item.time
        holder.ivIcon.setImageResource(item.iconRes)
        holder.tvTemp.text = item.temp
        holder.tvEtc.text  = item.extra
    }

    override fun getItemCount(): Int = items.size
}
