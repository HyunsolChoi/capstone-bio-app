package com.jjangdol.biorhythm.ui.measurement

import android.Manifest
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentPupilMeasurementBinding
import com.jjangdol.biorhythm.model.MeasurementState
import com.jjangdol.biorhythm.model.MeasurementType
import com.jjangdol.biorhythm.ui.view.FaceGuideOverlay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

@AndroidEntryPoint
class PupilMeasurementFragment : BaseMeasurementFragment() {

    /* ──────────── ViewBinding ──────────── */
    private var _binding: FragmentPupilMeasurementBinding? = null
    private val binding get() = _binding!!

    /* ───────────── Safe-Args ───────────── */
    private val args: PupilMeasurementFragmentArgs by navArgs()

    /* ───────────── Settings ────────────── */
    override val measurementType = MeasurementType.PUPIL
    override val requiredPermissions = arrayOf(Manifest.permission.CAMERA)
    override val nextNavigationAction = R.id.action_pupil_to_ppg

    /* ────────── Camera & ML Kit ────────── */
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private lateinit var faceDetector: FaceDetector

    // 측정 데이터
    private val blinkData = mutableListOf<Long>() // 눈 깜박임 시간
    private val eyeOpenRatios = mutableListOf<Float>() // 눈 개방 비율
    private var lastBlinkTime = 0L
    private var isEyeClosed = false
    private var measurementTimer: CountDownTimer? = null

    // 측정 설정
    private val MEASUREMENT_TIME = 15000L // 15초 측정
    private val EYE_CLOSED_THRESHOLD = 0.2f // 눈 감김 판단 임계값

    /* ---------- onCreateView: 레이아웃 inflate ---------- */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPupilMeasurementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        setupFaceDetector()
        setupUI()
    }

    // 마지막 측정(심박)이 아니므로 빈 함수로 구현
    override fun onLastMeasurementComplete(sessionId: String, rawData: String?) {
    }

    private fun setupFaceDetector() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()

        faceDetector = FaceDetection.getClient(options)
    }

    private fun setupUI() {
        binding.btnStart.setOnClickListener {
            startMeasurement()
        }

        // todo: 배포 시에는 건너뛰기 항목 모두 제거 예정
        binding.btnSkip.setOnClickListener {
            skipMeasurement()
        }

        binding.btnRetry.setOnClickListener {
            resetMeasurement()
            startMeasurement()
        }
    }

    override fun startMeasurement() {
        updateState(MeasurementState.Preparing)

        // UI 상태 변경
        binding.initialButtons.visibility = View.GONE
        binding.measurementInfo.visibility = View.VISIBLE
        binding.tvTimer.visibility = View.VISIBLE

        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview
            preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // Image Analysis
            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer())
                }

            // Camera Selector - 전면 카메라 사용
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )

                startActualMeasurement()

            } catch (e: Exception) {
                updateState(MeasurementState.Error("카메라를 시작할 수 없습니다: ${e.message}"))
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun startActualMeasurement() {
        updateState(MeasurementState.InProgress(0f))
        blinkData.clear()
        eyeOpenRatios.clear()
        lastBlinkTime = System.currentTimeMillis()

        // 얼굴 가이드 활성화
        binding.faceGuideOverlay.setGuideVisible(true)
        binding.faceGuideOverlay.updateFaceStatus(FaceGuideOverlay.FaceStatus.NO_FACE)

        // UI 업데이트
        binding.tvInstruction.text = "측정 중..."
        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgress.visibility = View.VISIBLE

        measurementTimer = object : CountDownTimer(MEASUREMENT_TIME, 100) {
            override fun onTick(millisUntilFinished: Long) {
                val progress = ((MEASUREMENT_TIME - millisUntilFinished).toFloat() / MEASUREMENT_TIME) * 100
                updateState(MeasurementState.InProgress(progress))

                val seconds = (millisUntilFinished / 1000) + 1
                binding.tvTimer.text = "${seconds}초"
                binding.tvProgress.text = "진행률: ${progress.toInt()}%"
            }

            override fun onFinish() {
                analyzeFatigue()
            }
        }.start()
    }

    private inner class FaceAnalyzer : ImageAnalysis.Analyzer {
        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                faceDetector.process(image)
                    .addOnSuccessListener { faces ->
                        processFaces(faces)
                    }
                    .addOnFailureListener { e ->
                        // 에러 처리
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }
        }
    }

    private fun processFaces(faces: List<Face>) {
        if (faces.isEmpty()) {
            binding.faceGuideOverlay.updateFaceStatus(FaceGuideOverlay.FaceStatus.NO_FACE)
            binding.tvFaceStatus.text = "얼굴을 가이드에 맞춰주세요"
            return
        }

        val face = faces[0] // 첫 번째 얼굴만 사용

        // 얼굴 위치 분석
        val faceBounds = face.boundingBox
        val faceStatus = analyzeFacePosition(face)
        binding.faceGuideOverlay.updateFaceStatus(faceStatus,
            android.graphics.RectF(faceBounds))

        // 눈 개방 정도 확인
        val leftEyeOpenProb = face.leftEyeOpenProbability ?: 0.5f
        val rightEyeOpenProb = face.rightEyeOpenProbability ?: 0.5f
        val avgEyeOpen = (leftEyeOpenProb + rightEyeOpenProb) / 2f

        eyeOpenRatios.add(avgEyeOpen)

        // 눈 깜박임 감지
        val currentTime = System.currentTimeMillis()
        if (avgEyeOpen < EYE_CLOSED_THRESHOLD && !isEyeClosed) {
            // 눈을 감기 시작
            isEyeClosed = true
        } else if (avgEyeOpen > EYE_CLOSED_THRESHOLD && isEyeClosed) {
            // 눈을 뜸 (깜박임 완료)
            isEyeClosed = false
            val blinkInterval = currentTime - lastBlinkTime
            blinkData.add(blinkInterval)
            lastBlinkTime = currentTime

            binding.tvBlinkCount.text = blinkData.size.toString()
        }

        // 상태 메시지 업데이트
        when (faceStatus) {
            FaceGuideOverlay.FaceStatus.FACE_PERFECT -> {
                binding.tvFaceStatus.text = "측정 중... 자연스럽게 깜빡여주세요"
            }
            FaceGuideOverlay.FaceStatus.FACE_TOO_FAR -> {
                binding.tvFaceStatus.text = "조금 더 가까이 와주세요"
            }
            FaceGuideOverlay.FaceStatus.FACE_TOO_CLOSE -> {
                binding.tvFaceStatus.text = "조금 더 멀리 가주세요"
            }
            FaceGuideOverlay.FaceStatus.FACE_NOT_CENTERED -> {
                binding.tvFaceStatus.text = "얼굴을 중앙으로 맞춰주세요"
            }
            else -> {
                binding.tvFaceStatus.text = "얼굴을 가이드에 맞춰주세요"
            }
        }
    }

    private fun analyzeFacePosition(face: Face): FaceGuideOverlay.FaceStatus {
        val faceBounds = face.boundingBox
        val faceWidth = faceBounds.width()
        val faceHeight = faceBounds.height()

        // 화면 크기 대비 얼굴 크기 체크
        val screenWidth = binding.previewView.width
        val screenHeight = binding.previewView.height

        val faceRatio = (faceWidth * faceHeight).toFloat() / (screenWidth * screenHeight)

        // 얼굴 중앙 위치 체크
        val faceCenterX = faceBounds.centerX()
        val faceCenterY = faceBounds.centerY()
        val screenCenterX = screenWidth / 2
        val screenCenterY = screenHeight / 2

        val distanceFromCenter = kotlin.math.sqrt(
            ((faceCenterX - screenCenterX) * (faceCenterX - screenCenterX) +
                    (faceCenterY - screenCenterY) * (faceCenterY - screenCenterY)).toDouble()
        )

        return when {
            faceRatio < 0.1f -> FaceGuideOverlay.FaceStatus.FACE_TOO_FAR
            faceRatio > 0.4f -> FaceGuideOverlay.FaceStatus.FACE_TOO_CLOSE
            distanceFromCenter > 100 -> FaceGuideOverlay.FaceStatus.FACE_NOT_CENTERED
            else -> FaceGuideOverlay.FaceStatus.FACE_PERFECT
        }
    }

    private fun analyzeFatigue() {
        measurementTimer?.cancel()
        stopCamera()

        lifecycleScope.launch {
            val score = withContext(Dispatchers.Default) {
                calculateFatigueScore()
            }

            val rawData = """
                {
                    "blinkCount": ${blinkData.size},
                    "avgBlinkInterval": ${if (blinkData.isNotEmpty()) blinkData.average() else 0},
                    "avgEyeOpenRatio": ${if (eyeOpenRatios.isNotEmpty()) eyeOpenRatios.average() else 0},
                    "measurementDuration": $MEASUREMENT_TIME
                }
            """.trimIndent()

            updateState(MeasurementState.Completed(
                com.jjangdol.biorhythm.model.MeasurementResult(
                    measurementType, score, rawData
                )
            ))

            // 결과 표시
            showResults(score, blinkData.size)

            // 다음 버튼 활성화
            binding.btnNext.setOnClickListener {
                onMeasurementComplete(score, rawData)
            }
        }
    }

    private fun stopCamera() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll() // 모든 카메라 바인딩 해제

            camera = null
            preview = null
            imageAnalyzer = null
        } catch (e: Exception) {
            // 에러 무시 (이미 해제된 경우)
        }
    }

    private fun showResults(score: Float, blinkCount: Int) {
        // 측정 진행 UI 숨기기
        binding.progressBar.visibility = View.GONE
        binding.tvProgress.visibility = View.GONE
        binding.measurementInfo.visibility = View.GONE
        binding.faceGuideOverlay.setGuideVisible(false)

        // 결과 카드 표시
        binding.resultCard.visibility = View.VISIBLE

        // 결과 아이콘 설정
        binding.ivResultIcon.setImageResource(
            when {
                score >= 80 -> R.drawable.ic_visibility
                score >= 60 -> R.drawable.ic_warning
                else -> R.drawable.ic_error
            }
        )

        // 아이콘 색상 설정
        binding.ivResultIcon.setColorFilter(
            ContextCompat.getColor(requireContext(), when {
                score >= 80 -> R.color.safety_safe
                score >= 60 -> R.color.safety_caution
                else -> R.color.safety_danger
            })
        )

        // 결과 텍스트
        binding.tvResult.text = "피로도 점수: ${score.toInt()}점"
        binding.tvResultDetail.text = when {
            score >= 80 -> "눈 상태가 매우 좋습니다"
            score >= 60 -> "약간의 피로가 감지됩니다"
            score >= 40 -> "휴식이 필요한 상태입니다"
            else -> "심한 피로 상태입니다"
        }

        // 측정 세부사항
        binding.tvFinalBlinkCount.text = "${blinkCount}회"
        binding.tvMeasurementTime.text = "15초"

        // 버튼 그룹 변경
        binding.initialButtons.visibility = View.GONE
        binding.resultButtons.visibility = View.VISIBLE
    }

    private fun calculateFatigueScore(): Float {
        val expectedBlinks = 4.25f // 이상적인 깜빡임 수 (15초 기준)

        // 스냅샷 복사본 사용 (동시 수정 방지)
        val blinkSnapshot = blinkData.toList()
        val eyeOpenSnapshot = eyeOpenRatios.toList()

        val actualBlinks = blinkSnapshot.size.toFloat()

        // 깜빡임 점수: 이상적인 범위 (7~10) 기준 가우시안 감점 적용
        val blinkScore = (100 - abs(actualBlinks - expectedBlinks) * 10).coerceIn(0f, 100f)

        // 평균 눈 개방 정도
        val avgEyeOpen = if (eyeOpenSnapshot.isNotEmpty()) eyeOpenSnapshot.average().toFloat() else 0.5f

        // 눈 개방 점수: 이상적인 범위 0.85~0.95 중심으로 최고점 부여
        val idealEyeOpen = 0.9f
        val eyeOpenScore = (100 - abs(avgEyeOpen - idealEyeOpen) * 200).coerceIn(0f, 100f)

        // 깜빡임 간격의 표준편차 (일관성 점수)
        val blinkIntervalStdDev = if (blinkSnapshot.size > 1) {
            val avgInterval = blinkSnapshot.average()
            val variance = blinkSnapshot.map { (it - avgInterval).let { diff -> diff * diff } }.average()
            kotlin.math.sqrt(variance).toFloat()
        } else 0f

        val consistencyScore = when {
            blinkIntervalStdDev <= 1000 -> 100f
            blinkIntervalStdDev <= 3000 -> (100f - (blinkIntervalStdDev - 1000) / 20f).coerceIn(0f, 100f)
            else -> 0f
        }

        // 가중 평균 계산 (blink 50%, eyeOpen 30%, consistency 20%)
        val finalScore = (blinkScore * 0.5f + eyeOpenScore * 0.3f + consistencyScore * 0.2f).coerceIn(0f, 100f)

        return finalScore
    }



    override fun onStateChanged(state: MeasurementState) {
        when (state) {
            is MeasurementState.Preparing -> {
                binding.btnStart.isEnabled = false
            }
            is MeasurementState.InProgress -> {
                binding.progressBar.progress = state.progress.toInt()
            }
            is MeasurementState.Completed -> {
                // showResults()에서 처리됨
            }
            is MeasurementState.Error -> {
                binding.tvInstruction.text = state.message
                binding.btnStart.isEnabled = true
                binding.initialButtons.visibility = View.VISIBLE
                binding.resultButtons.visibility = View.GONE
                binding.progressBar.visibility = View.GONE
                binding.measurementInfo.visibility = View.GONE
                binding.faceGuideOverlay.setGuideVisible(false)
            }
            else -> {}
        }
    }

    private fun resetMeasurement() {
        measurementTimer?.cancel()
        stopCamera()
        blinkData.clear()
        eyeOpenRatios.clear()

        // UI 초기화
        binding.btnStart.isEnabled = true
        binding.initialButtons.visibility = View.VISIBLE
        binding.resultButtons.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.tvProgress.visibility = View.GONE
        binding.resultCard.visibility = View.GONE
        binding.measurementInfo.visibility = View.GONE
        binding.faceGuideOverlay.setGuideVisible(false)

        binding.tvInstruction.text = "얼굴을 가이드에 맞춰주세요"
        binding.tvTimer.text = ""
        binding.tvBlinkCount.text = "0"
        binding.tvFaceStatus.text = ""
    }

    override fun onDestroyView() {
        super.onDestroyView()
        measurementTimer?.cancel()
        stopCamera()
        cameraExecutor.shutdown()
        _binding = null
    }
}