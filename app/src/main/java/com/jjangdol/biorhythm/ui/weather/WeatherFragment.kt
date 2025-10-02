// app/src/main/java/com/jjangdol/biorhythm/ui/weather/WeatherFragment.kt
package com.jjangdol.biorhythm.ui.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.material.snackbar.Snackbar
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentWeatherBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.time.LocalTime
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import android.widget.TextView
import android.util.TypedValue


class WeatherFragment : Fragment(R.layout.fragment_weather) {

    private var _binding: FragmentWeatherBinding? = null
    private val binding get() = _binding!!
    private lateinit var fused: FusedLocationProviderClient
    private var currentLocCts: CancellationTokenSource? = null


    /** 사용자의 위치 권한 요청 → 응답에 따른 처리 */
    private val requestLocationPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
    { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
                || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                //FINE:정밀위치,COARSE:지리상 대략적 위치

        if (granted)
        {
            showLoading(true)
            fetchLastLocationAndUpdateUI()
            bindRandomDummyWeather()
            showLoading(false)
        }
        else
        { Toast.makeText(requireContext(), "위치 권한이 없어 기본 위치를 표시합니다.", Toast.LENGTH_SHORT).show() }
    }

    /** 사용자의 위치 권한이 확인되면 → 허용 시 위치 조회 */
    @SuppressLint("MissingPermission")
    private fun updateLocationName()
    {
        val fine = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
        //fine:정밀위치,coarse:지리상 대략적 위치

        //두 위치가 모두 권한이 확인 되지 않으면 (권한 미허용)
        //둘 중 하나만 권한이 허용되어도 위치는 사용 가능
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED)
        {
            // 권한요청
            requestLocationPerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }

        //최근 위치 가져오기 (최근 시스템이 알고 있는 위치)
        // 클래스 필드로 초기화한 fused 사용 : onViewCreated에서 초기화됨
        fused.lastLocation
            .addOnSuccessListener { loc ->
                if (loc == null) //기기가 한 번도 위치를 얻은 적 없거나 캐시가 없으면 loc==null
                {
                    fetchCurrentLocationFallback() //대체 경로 사용
                    return@addOnSuccessListener
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    val name = withContext(Dispatchers.IO) { reverseGeocodeToShortName(loc.latitude, loc.longitude) } //지명을 얻지 못하면 위도(latitude),경도(longitude) 얻음
                    val display = name ?: "(${String.format(Locale.US, "%.4f", loc.latitude)}, ${String.format(Locale.US, "%.4f", loc.longitude)})"
                    binding.tvLocation.text  = display

                    bindRandomDummyWeather()
                }
            }
            .addOnFailureListener { Toast.makeText(requireContext(), "위치 조회 실패", Toast.LENGTH_SHORT).show() }
    }

    /** 기기의 최근 위치가 확인이 안 된다면, 대체(백업) 경로 가져오기 */
    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocationFallback()
    {
        currentLocCts?.cancel() //이전 위치 요청을 관리하는 취소 토큰
        val cts = CancellationTokenSource() //이번에 할 요청을 관리할 토큰

        //현재 위치 요청
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null)
                {
                    updateAddressFrom(loc.latitude, loc.longitude)
                }
                else
                {
                    Toast.makeText(requireContext(), "현재 위치를 가져올 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { Toast.makeText(requireContext(), "현재 위치 요청 실패", Toast.LENGTH_SHORT).show() }
    }

    /** 마지막으로 저장된 기기의 위치로 주소명 갱신 */
    @SuppressLint("MissingPermission")
    private fun fetchLastLocationAndUpdateUI()
    {
        fused.lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null)
                {
                    updateAddressFrom(loc.latitude, loc.longitude)
                    bindRandomDummyWeather()
                }
                else
                {
                    Toast.makeText(requireContext(), "현재 위치를 가져올 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener{ Toast.makeText(requireContext(), "위치 조회 실패", Toast.LENGTH_SHORT).show() }
    }

    /** 위도,경도 값을 주소명으로 변환 */
    private fun updateAddressFrom(lat: Double, lon: Double)
    {
        viewLifecycleOwner.lifecycleScope.launch{
            val name = withContext(Dispatchers.IO)
            {
                reverseGeocodeToShortName(lat, lon)
            }
            val display = name ?: "(${lat.f(4)}, ${lon.f(4)})"
            binding.tvLocation.text  = display
        }
    }

    /** 위경도를 "서울특별시 종로구 00동" 식으로 변환 (없으면 null) */
    private fun reverseGeocodeToShortName(lat: Double, lon: Double): String?
    {
        return try
        {
            if (!Geocoder.isPresent()) return null //Geocoder 사용 가능한지 확인

            val g = Geocoder(requireContext(), Locale.KOREA)
            val list: List<Address> = g.getFromLocation(lat, lon, 1) ?: emptyList() //1건만 가져옴

            if (list.isEmpty()) return null
            val a = list[0]

            // --- '도' 정보 추출 ---
            val province = a.adminArea

            // 단말/OS별로 필드가 다를 수 있으니 안전하게 조합
            val wiedarea     = a.locality ?: a.adminArea       // 서울특별시, 전주시, 경기도 등
            val narrowarea   = a.subLocality ?: a.subAdminArea // 종로구 / 덕진구 등
            val detailarea   = a.thoroughfare ?: a.featureName      // 효자동 or 도로명

            when
            {
                !wiedarea.isNullOrBlank() && !narrowarea.isNullOrBlank() && !detailarea.isNullOrBlank() -> "$wiedarea $narrowarea $detailarea"
                !wiedarea.isNullOrBlank() && !narrowarea.isNullOrBlank() -> "$wiedarea $narrowarea"
                !narrowarea.isNullOrBlank() -> narrowarea
                !wiedarea.isNullOrBlank() -> wiedarea
                !a.featureName.isNullOrBlank() -> a.featureName
                else -> null
            }
        }
        catch (_: Exception) { null }
    }

    /** 새로고침 로딩 */
    private fun showLoading(show: Boolean)
    {
        binding.progress.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnRefresh.isEnabled = !show
        setNowSkeleton(show)
    }
    private fun setNowSkeleton(show: Boolean)
    {
        if (show)
        {
            binding.ivNowIcon.imageAlpha = 80
            binding.tvNowTemp.text = "--°"
            binding.tvNowDesc.text = "불러오는 중…"
            binding.tvHumidity.text = "습도 --%"
            binding.tvRain.text = "강수 --"
        }
        else
        {
            binding.ivNowIcon.imageAlpha = 255
        }
    }

    /** 최초 한 번 기본값 (권한 거부/지오코더 실패 대비) : 일단 하드코딩*/
    private fun bindDummyWeatherOnce() {
        binding.tvLocation.text  = "현재 위치"
        binding.tvUpdated.text   = "업데이트: --:--"

        binding.ivNowIcon.setImageResource(R.drawable.ic_weather) // 임시 아이콘
        binding.tvNowTemp.text = "00°"
        binding.tvNowDesc.text = "(맑음) · 체감온도 00°"
        binding.tvHumidity.text = "습도 00%"
        binding.tvRain.text     = "강수 0mm"
    }

    /** 새로고침 시 더미 날씨를 랜덤으로 바인딩 */
    private fun bindRandomDummyWeather()
    {
        val list = listOf(
            Dummy("맑음 · 체감온도 00°", "00°", "습도 00%", "강수 0mm",   R.drawable.ic_weather),
            Dummy("가끔 구름 · 체감온도 00°", "00°", "습도 00%", "강수 0mm", R.drawable.ic_weather),
            Dummy("비 · 체감온도 00°",   "00°", "습도 00%", "강수 0mm",   R.drawable.ic_weather)
        )

        val d = list.random()
        binding.tvNowDesc.text = d.desc
        binding.tvNowTemp.text = d.temp
        binding.tvHumidity.text = d.humidity
        binding.tvRain.text = d.rain
        binding.ivNowIcon.setImageResource(d.icon)

        val now = LocalTime.now().withSecond(0).withNano(0).toString()
        binding.tvUpdated.text = "업데이트: $now"

        bindGuidelines(d.desc)
    }
    private data class Dummy(
        val desc: String,
        val temp: String,
        val humidity: String,
        val rain: String,
        val icon: Int
    )


    override fun onDestroyView()
    {
        super.onDestroyView()
        _binding = null
    }

    /** 환영문구 */
    private fun greetUser()
    {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", null)

        if (!name.isNullOrBlank())
        {
            binding.tvUserName.text = name
            binding.tvWelcome.text = "님, 환영합니다."
        }
        else
        {
            binding.tvUserName.text = ""
            binding.tvWelcome.text = "환영합니다."
        }
    }

    /** 지침사항 */
    private fun bindGuidelines(condition: String)
    {
        // 제목/부제
        binding.tvGuidelineTitle.text = "안전 지침"

        val (subtitleRes, arrayRes) = when
        {
            condition.contains("맑음") -> R.string.guideline_sunny_subtitle to R.array.guidelines_sunny
            condition.contains("비") -> R.string.guideline_rain_subtitle  to R.array.guidelines_rain
            condition.contains("눈") -> R.string.guideline_snow_subtitle  to R.array.guidelines_snow
            else -> R.string.guideline_title to R.array.guidelines_default
        }
        binding.tvGuidelineSubtitle.text = getString(subtitleRes)

        // 기존 항목 비우기
        binding.guidelineContainer.removeAllViews()

        val items = resources.getStringArray(arrayRes)

        val pad = (8 * resources.displayMetrics.density).toInt()
        items.forEach { line ->
            val tv = android.widget.TextView(requireContext()).apply {
                text = "$line"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary))
                setPadding(0, pad / 2, 0, pad / 2)
            }
            binding.guidelineContainer.addView(tv)
        }
    }

    /** 소수점 포맷 */
    private fun Double.f(d: Int) = String.format(Locale.US, "%.${d}f", this)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?)
    {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentWeatherBinding.bind(view)

        fused = LocationServices.getFusedLocationProviderClient(requireContext())

        //유저환영
        greetUser()
        // 초기 더미 바인딩
        bindDummyWeatherOnce()
        // 현재 위치명 시도
        updateLocationName()

        // 새로고침: 로딩 → (더미)날씨 갱신 + 위치명 갱신
        binding.btnRefresh.setOnClickListener{
            showLoading(true)  // 네트워크 대기 연출

            binding.btnRefresh.postDelayed({
                showLoading(false)
                bindRandomDummyWeather()
                updateLocationName()
            }, 1200)
        }
    }
}