package com.jjangdol.biorhythm.ui.weather

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.FusedLocationProviderClient
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentWeatherBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import android.widget.EditText
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.math.*
import com.google.firebase.firestore.Source
import android.text.InputFilter
import android.text.InputType
import android.text.method.DigitsKeyListener

class WeatherFragment : Fragment(R.layout.fragment_weather) {

    private var _binding: FragmentWeatherBinding? = null
    private val binding get() = _binding!!
    private lateinit var fused: FusedLocationProviderClient
    private var currentLocCts: CancellationTokenSource? = null
    private val db = FirebaseFirestore.getInstance()
    private lateinit var functions: FirebaseFunctions


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
            showLoading(false)
        }
        else
        { Toast.makeText(requireContext(), "위치 권한이 없어 기본 위치를 표시합니다.", Toast.LENGTH_SHORT).show() }
    }

    /** 사용자의 위치 권한이 확인되면 → 허용 시 위치 조회 */

    @SuppressLint("MissingPermission")
    private fun updateLocationName() {
        val fine = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            requestLocationPerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }

        fused.lastLocation
            .addOnSuccessListener { loc ->
                if (loc == null) {
                    fetchCurrentLocationFallback()
                    return@addOnSuccessListener
                }

                // 코루틴을 Main 스레드에서 시작하고, 필요한 부분만 IO 스레드로 전환합니다.
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    showLoading(true)

                    try {
                        // 주소 변환
                        val name = withContext(Dispatchers.IO) {
                            reverseGeocodeToShortName(loc.latitude, loc.longitude)
                        }
                        binding.tvLocation.text = name ?: "위치 정보 없음"

                        // 격자 변환 및 날씨 정보 가져와서 UI 업데이트 (suspend 함수 호출)
                        val (nx, ny) = convertToGrid(loc.latitude, loc.longitude)
                        getWeatherAndUpdateUI(nx, ny) // 이 함수가 끝날 때까지 아래 코드는 실행되지 않음

                    } catch (e: Exception) {
                        // 코루틴 내에서 발생할 수 있는 예외 처리
                        Log.e("WeatherDebug", "updateLocationName 내 코루틴 오류", e)
                    } finally {
                        // 모든 작업이 끝난 후 로딩 종료
                        delay(50) // UI 렌더링을 위한 짧은 지연
                        showLoading(false)
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "위치 조회에 실패했습니다.", Toast.LENGTH_SHORT).show()
                // 위치 조회 실패 시에도 로딩 상태는 해제
                showLoading(false)
            }
    }

    /** 위도 경도 -> nx ny 변환 함수를 위한 값 */
    data class LamcParameter(
        val Re: Double = 6371.00877, // 지구 반경 (km)
        val grid: Double = 5.0,       // 격자 간격 (km)
        val slat1: Double = 30.0,     // 표준 위도 1
        val slat2: Double = 60.0,     // 표준 위도 2
        val olon: Double = 126.0,     // 기준점 경도
        val olat: Double = 38.0,      // 기준점 위도
        val xo: Double = 43.0,        // 기준점 X좌표
        val yo: Double = 136.0        // 기준점 Y좌표
    )
    /** 위도 경도 -> nx ny 변환 함수 */
    fun convertToGrid(lat: Double, lon: Double): Pair<Int, Int> {
        val map = LamcParameter()

        val PI = Math.asin(1.0) * 2.0
        val DEGRAD = PI / 180.0

        val re = map.Re / map.grid
        val slat1 = map.slat1 * DEGRAD
        val slat2 = map.slat2 * DEGRAD
        val olon = map.olon * DEGRAD
        val olat = map.olat * DEGRAD

        var sn = Math.tan(PI * 0.25 + slat2 * 0.5) / Math.tan(PI * 0.25 + slat1 * 0.5)
        sn = Math.log(Math.cos(slat1) / Math.cos(slat2)) / Math.log(sn)
        var sf = Math.tan(PI * 0.25 + slat1 * 0.5)
        sf = Math.pow(sf, sn) * Math.cos(slat1) / sn
        var ro = Math.tan(PI * 0.25 + olat * 0.5)
        ro = re * sf / Math.pow(ro, sn)

        var ra = Math.tan(PI * 0.25 + lat * DEGRAD * 0.5)
        ra = re * sf / Math.pow(ra, sn)
        var theta = lon * DEGRAD - olon
        if (theta > PI) theta -= 2.0 * PI
        if (theta < -PI) theta += 2.0 * PI
        theta *= sn

        val x = (ra * Math.sin(theta) + map.xo + 0.5).toInt()
        val y = (ro - ra * Math.cos(theta) + map.yo + 0.5).toInt()

        return Pair(x, y)
    }


    /** Cloud Function을 호출하고 날씨 정보로 UI를 업데이트하는 함수 */
    private suspend fun getWeatherAndUpdateUI(nx: Int, ny: Int) {
        try {
            Log.d("WeatherDebug", "getWeatherData 호출 시작: nx=$nx, ny=$ny")

            val result = functions
                .getHttpsCallable("getWeatherData")
                .call(mapOf("nx" to nx, "ny" to ny))
                .await()

            val outer = result.data as? Map<*, *>
            val weatherData = outer?.get("data") as? Map<String, String>

            Log.d("WeatherDebug", "받은 원본 데이터: $outer")
            Log.d("WeatherDebug", "실제 날씨 데이터: $weatherData")

            if (weatherData != null) {
                binding.tvNowTemp.text = "${weatherData["T1H"] ?: "--"}°"
                binding.tvHumidity.text = "습도 ${weatherData["REH"] ?: "--"}%"
                binding.tvRain.text = "강수 ${weatherData["RN1"] ?: "--"}"

                val skyValue = weatherData["SKY"]
                val ptyValue = weatherData["PTY"]

                val (condition, iconResId) = mapWeatherCondition(skyValue, ptyValue)
                binding.ivNowIcon.setImageResource(iconResId)

                val temp = weatherData["T1H"]?.toDoubleOrNull()
                val humidity = weatherData["REH"]?.toDoubleOrNull()

                // 체감온도 (기상청 기상자료 공개 포털 참고)
                if (temp != null && humidity != null) {
                    val currentMonth = LocalDate.now().monthValue
                    if (currentMonth in 5..9) {
                        // 여름(5~9월)만 체감온도 표시
                        val sensible = calculateSensibleTemperature(temp, humidity)
                        binding.tvNowDesc.text = "$condition · 체감온도 ${"%.1f".format(sensible)}°"
                    } else {
                        // 10~5월은 체감온도 미표시
                        binding.tvNowDesc.text = condition
                    }
                } else {
                    binding.tvNowDesc.text = "$condition · 체감온도 --°"
                }

                bindGuidelines(condition)
            } else {
                Toast.makeText(requireContext(), "날씨 정보를 받아오지 못했습니다.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("WeatherDebug", "getWeatherData 호출 실패", e)
            Toast.makeText(requireContext(), "날씨 정보를 가져오는 중 오류: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 날씨 코드(SKY, PTY)를 한글 날씨 상태로 변환하는 도우미 함수 */
    private fun mapWeatherCondition(sky: String?, pty: String?): Pair<String, Int> {
        return when (pty) {
            "0" -> when (sky) {
                "1" -> "맑음" to R.drawable.ic_weather_sunny
                "3" -> "구름많음" to R.drawable.ic_weather_cloudy
                "4" -> "흐림" to R.drawable.ic_weather_cloudy
                else -> "알 수 없음"
            }

            "1" -> "비" to R.drawable.ic_weather_rainy
            "2" -> "비/눈" to R.drawable.ic_weather_rain_and_snow
            "3" -> "눈" to R.drawable.ic_weather_snowy
            "4" -> "소나기" to R.drawable.ic_weather_rainy
            "5" -> "빗방울" to R.drawable.ic_weather_rainy
            "6" -> "빗방울/눈날림" to R.drawable.ic_weather_rain_and_snow
            "7" -> "눈날림" to R.drawable.ic_weather_snowy
            else -> "알 수 없음"
        } as Pair<String, Int>
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

            val wiedarea = a.locality ?: a.adminArea       // 시/도 (서울특별시, 전주시)
            val narrowarea = a.subLocality ?: a.subAdminArea // 구 (종로구, 덕진구)

            // 동/도로명/지형지물 이름 추출
            val thoroughfare = a.thoroughfare // 도로명 주소 또는 동 이름 (e.g., "금암동", "세종대로")
            val featureName = a.featureName   // 지형/건물 이름 (e.g., "경복궁", "금암동")

            val detailarea = when {
                // thoroughfare에 유효한 문자열이 있고 숫자가 아니라면 (주소 정보 방지)
                thoroughfare?.any { it.isLetter() } == true -> thoroughfare
                // 그렇지 않다면 featureName을 사용
                else -> featureName
            }

            when {
                !wiedarea.isNullOrBlank() && !narrowarea.isNullOrBlank() && !detailarea.isNullOrBlank() ->
                    "$wiedarea $narrowarea $detailarea"
                !wiedarea.isNullOrBlank() && !narrowarea.isNullOrBlank() ->
                    "$wiedarea $narrowarea"
                !wiedarea.isNullOrBlank() && !detailarea.isNullOrBlank() -> // 구 정보가 없을 경우 대비
                    "$wiedarea $detailarea"
                !wiedarea.isNullOrBlank() -> wiedarea
                else -> null // 모든 정보가 없을 경우
            }
        }
        catch (_: Exception) { null }
    }

    /** 새로고침 로딩 */
    private fun showLoading(show: Boolean)
    {
        Log.d("WeatherDebug", "showLoading($show)")
        binding.progress.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnRefresh.isEnabled = !show
        if (show) setNowSkeleton(true)
    }

    private fun setNowSkeleton(show: Boolean) {
        if (show) {
            binding.ivNowIcon.imageAlpha = 80
            binding.tvNowTemp.text = "--°"
            binding.tvNowDesc.text = "불러오는 중…"
            binding.tvHumidity.text = "습도 --%"
            binding.tvRain.text = "강수 --"
        } else {
            binding.ivNowIcon.imageAlpha = 255
             binding.tvNowTemp.text = "--°"
            binding.tvNowDesc.text = "불러오는 중…"
            binding.tvHumidity.text = "습도 --%"
            binding.tvRain.text = "강수 --"
        }
    }

    /** Stull 근사식을 이용한 습구온도 계산 */
    fun calculateWetBulbTemperature(tempC: Double, humidity: Double): Double {
        return tempC * atan(0.151977 * sqrt(humidity + 8.313659)) +
                atan(tempC + humidity) -
                atan(humidity - 1.676331) +
                0.00391838 * humidity.pow(1.5) * atan(0.023101 * humidity) -
                4.686035
    }

    /** 기상청 체감온도 공식 */
    fun calculateSensibleTemperature(tempC: Double, humidity: Double): Double {
        val Tw = calculateWetBulbTemperature(tempC, humidity)
        return -0.2442 + (0.55399 * Tw) + (0.45535 * tempC) -
                (0.0022 * Tw * Tw) + (0.00278 * Tw * tempC) + 3.0
    }

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
            binding.tvWelcome.text = "님 환영합니다"
        }
        else
        {
            binding.tvUserName.text = ""
            binding.tvWelcome.text = "환영합니다"
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

    /** 관리자 버튼 표시 여부 결정 */
    private fun checkAdminVisibility() {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val empNum = prefs.getString("emp_num", null)

        if (empNum.isNullOrEmpty()) {
            binding.tvAdminLink.visibility = View.GONE
            return
        }

        db.collection("employees").document(empNum)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists() && doc.contains("Password")) {
                    // 관리자 비밀번호 필드가 있는 경우만 버튼 표시
                    binding.tvAdminLink.visibility = View.VISIBLE
                    Log.d("AdminCheck", "관리자 계정 확인됨 → 버튼 표시")
                } else {
                    // 일반 직원은 버튼 숨김
                    binding.tvAdminLink.visibility = View.GONE
                    Log.d("AdminCheck", "일반 계정 → 버튼 숨김")
                }
            }
            .addOnFailureListener { e ->
                Log.e("AdminCheck", "Firestore 오류: ${e.message}")
                binding.tvAdminLink.visibility = View.GONE
            }
    }

    /** 소수점 포맷 */
    private fun Double.f(d: Int) = String.format(Locale.US, "%.${d}f", this)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?)
    {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentWeatherBinding.bind(view)

        functions = Firebase.functions("asia-northeast3")
        fused = LocationServices.getFusedLocationProviderClient(requireContext())

        //유저환영
        greetUser()
        // 초기 로딩 UI 설정
        setNowSkeleton(true)
        // 현재 위치명 시도
        updateLocationName()

        // 새로고침
        binding.btnRefresh.setOnClickListener {
            updateLocationName()
        }

        // 관리자 여부 확인 후 버튼 표시/숨김
        checkAdminVisibility()

        // 관리자 버튼
        binding.tvAdminLink.setOnClickListener {
            val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val empNum = prefs.getString("emp_num", null) ?: return@setOnClickListener  // 현재 로그인한 사번 불러오기

            val input = EditText(requireContext()).apply {
                hint = "관리자 비밀번호"
                inputType = InputType.TYPE_CLASS_NUMBER
                keyListener = DigitsKeyListener.getInstance("0123456789")
            }

            val dialog = AlertDialog.Builder(requireContext())
                .setTitle("관리자 비밀번호 입력")
                .setView(input)
                .setPositiveButton("확인", null)
                .setNegativeButton("취소", null)
                .create()

            dialog.setOnShowListener{
                val okBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                okBtn.setOnClickListener {
                    val password = input.text.toString().trim()
                    if (password.isEmpty())
                    {
                        Toast.makeText(requireContext(), "비밀번호를 입력하세요.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val db = FirebaseFirestore.getInstance()
                    db.collection("employees")
                        .document(empNum)
                        .get(Source.SERVER)
                        .addOnSuccessListener { doc ->
                            if (!doc.exists()) {
                                Toast.makeText(requireContext(), "관리자 계정이 존재하지 않습니다", Toast.LENGTH_SHORT).show()
                                return@addOnSuccessListener
                            }

                            val savedPwStr = doc.getString("Password")

                            if (savedPwStr == password) {
                                Toast.makeText(requireContext(), "관리자 로그인 성공", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                                val mainNavController = requireActivity().findNavController(R.id.navHostFragment)
                                mainNavController.navigate(R.id.action_main_to_newAdmin)
                            } else { Toast.makeText(requireContext(), "비밀번호가 올바르지 않습니다", Toast.LENGTH_SHORT).show() }
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(requireContext(), "Firestore 오류: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            dialog.show()
        }
    }
}