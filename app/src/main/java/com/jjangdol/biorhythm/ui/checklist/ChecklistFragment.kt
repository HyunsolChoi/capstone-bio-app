package com.jjangdol.biorhythm.ui.checklist

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.data.UserRepository
import com.jjangdol.biorhythm.databinding.FragmentChecklistBinding
import com.jjangdol.biorhythm.model.ChecklistResult
import com.jjangdol.biorhythm.model.SafetyCheckSession
import com.jjangdol.biorhythm.util.ScoreCalculator
import com.jjangdol.biorhythm.vm.ChecklistViewModel
import com.jjangdol.biorhythm.vm.SafetyCheckViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ChecklistFragment : Fragment(R.layout.fragment_checklist) {

    private var _binding: FragmentChecklistBinding? = null
    private val binding get() = _binding!!

    private val checklistViewModel: ChecklistViewModel by viewModels()
    private val safetyCheckViewModel: SafetyCheckViewModel by activityViewModels()

    @Inject
    lateinit var userRepository: UserRepository

    private val dateFormatter = DateTimeFormatter.ISO_DATE
    private lateinit var sessionId: String

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentChecklistBinding.bind(view)

        // 1) 새 세션 시작
        sessionId = UUID.randomUUID().toString()
        initializeSafetyCheckSession()

        // 2) UI 초기화
        setupRecyclerView()
        loadUserData()
        setupSubmitButton()
        observeViewModel()

        // 3) 이미지 애니메이션
        binding.ivChecklist.apply {
            alpha = 0f
            scaleX = 0.6f
            scaleY = 0.6f
            rotation = -90f

            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .rotation(0f)
                .setDuration(1000L)
                .start()
        }
    }

    private fun initializeSafetyCheckSession() {
        val userId = getUserId() ?: return
        val session = SafetyCheckSession(
            sessionId = sessionId,
            userId = userId,
            startTime = System.currentTimeMillis()
        )
        safetyCheckViewModel.startNewSession(session)
    }

    private fun getUserId(): String? {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", "") ?: ""
        val empNum = prefs.getString("user_empNum", "") ?: ""

        return if (name.isNotEmpty() && empNum.isNotEmpty()) {
            userRepository.getUserId(name, empNum)
        } else {
            null
        }
    }

    private fun setupRecyclerView() {
        val adapter = ChecklistAdapter(emptyList()) { position, isYes ->
            checklistViewModel.answerChanged(position, isYes)
        }

        binding.rvChecklist.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
            setHasFixedSize(true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            checklistViewModel.items.collect { items ->
                adapter.updateItems(items)

                // 전체/완료 개수 업데이트
                val total = items.size
                val completed = items.count { it.answeredYes != null }
                val rate = if (total > 0) (completed * 100 / total) else 0

                binding.tvTotalCount.text = total.toString()
                binding.tvCompletedCount.text = completed.toString()
                binding.tvCompletionRate.text = "$rate%"

                // 진행률 바 업데이트 (max가 total이므로 progress = completed)
                binding.checklistProgressBar.max = total
                binding.checklistProgressBar.progress = completed

                // 제출 버튼 활성화 여부
                binding.btnSubmit.isEnabled = items.all { it.answeredYes != null }
            }
        }
    }

    private fun loadUserData() {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val dobStr = prefs.getString("dob", LocalDate.now().format(dateFormatter))!!
        val dob = LocalDate.parse(dobStr, dateFormatter)
    }

    private fun setupSubmitButton() = with(binding) {
        btnSubmit.setOnClickListener { submitChecklistAndProceed() }
    }

    private fun observeViewModel() {
        safetyCheckViewModel.sessionState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is SafetyCheckViewModel.SessionState.Loading -> {
                    binding.loadingOverlay.visibility = View.VISIBLE
                    binding.btnSubmit.isEnabled = false
                }
                is SafetyCheckViewModel.SessionState.Success -> {
                    binding.loadingOverlay.visibility = View.GONE
                    navigateToTremorMeasurement()
                }
                is SafetyCheckViewModel.SessionState.Error -> {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.btnSubmit.isEnabled = true
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                }
                else -> {
                    binding.loadingOverlay.visibility = View.GONE
                }
            }
        }
    }

    private fun submitChecklistAndProceed() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                binding.loadingOverlay.visibility = View.VISIBLE
                binding.btnSubmit.isEnabled = false

                // 1) 점수 계산
                val items = checklistViewModel.items.value
                val checklistScore = ScoreCalculator.calcChecklistScore(items)

                // 2) 세션 업데이트
                safetyCheckViewModel.updateChecklistResults(
                    checklistItems = items,
                    checklistScore = checklistScore,
                )

                // 3) Firebase 기록 (호환용)
                saveResultToFirestore(checklistScore)

            } catch (e: Exception) {
                showError("오류가 발생했습니다: ${e.message}")
            }
        }
    }

    private fun saveResultToFirestore(checklistScore: Int) {
        val today = LocalDate.now().format(dateFormatter)
        val userId = getUserId()

        if (userId == null) {
            showError("사용자 정보를 찾을 수 없습니다")
            return
        }

        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

        val result = ChecklistResult(
            userId = userId,
            name = prefs.getString("user_name", "") ?: "",
            dept = prefs.getString("user_dept", "") ?: "",
            checklistScore = checklistScore,
            finalScore = checklistScore,
            date = today
        )

        Firebase.firestore.collection("results")
            .document(today)
            .collection("entries")
            .document(userId)
            .set(result)
            .addOnSuccessListener {
                // 중복으로 저장 (userId 기준)
                Firebase.firestore.collection("results")
                    .document(userId)
                    .collection("daily")
                    .document(today)
                    .set(result)
                    .addOnSuccessListener {
                        binding.loadingOverlay.visibility = View.GONE
                        Toast.makeText(requireContext(), "제출 완료", Toast.LENGTH_SHORT).show()
                        navigateToTremorMeasurement()
                    }
                    .addOnFailureListener { e ->
                        showError("제출 실패: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                showError("제출 실패: ${e.message}")
            }
    }

    private fun navigateToTremorMeasurement() {
        try {
            val bundle = bundleOf("sessionId" to sessionId)
            requireActivity()
                .findNavController(R.id.navHostFragment)
                .navigate(R.id.tremorMeasurementFragment, bundle)
        } catch (e: Exception) {
            showError("화면 이동 실패: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun showError(msg: String) {
        binding.loadingOverlay.visibility = View.GONE
        binding.btnSubmit.isEnabled = true
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}