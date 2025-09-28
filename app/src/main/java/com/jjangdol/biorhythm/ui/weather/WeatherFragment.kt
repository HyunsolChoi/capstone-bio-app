package com.jjangdol.biorhythm.ui.weather

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentWeatherBinding

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass.
 * Use the [BlankFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class WeatherFragment : Fragment(R.layout.fragment_weather) {
    private var _binding: FragmentWeatherBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentWeatherBinding.bind(view)

        // TODO: 실제 날씨 데이터 바인딩
        binding.tvTemperature.text = "예시) 맑음 · 26°C · 미세먼지 보통"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}