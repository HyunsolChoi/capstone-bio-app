// app/src/main/java/com/jjangdol/biorhythm/ui/admin/AdminResultsFragment.kt
package com.jjangdol.biorhythm.ui.admin

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentAdminResultsBinding
import com.jjangdol.biorhythm.data.ResultsRepository
import com.jjangdol.biorhythm.model.ChecklistResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@AndroidEntryPoint
class AdminResultsFragment : Fragment(R.layout.fragment_admin_results) {

    private var _b: FragmentAdminResultsBinding? = null
    private val b get() = _b!!

    @Inject lateinit var repo: ResultsRepository

    // ChecklistResult를 직접 사용하는 어댑터
    private val riskAdapter = AdminResultsAdapter { item ->
        showResultDetail(item)
    }

    private val safeAdapter = AdminResultsAdapter { item ->
        showResultDetail(item)
    }

    companion object {
        private const val SAFETY_THRESHOLD = 50
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentAdminResultsBinding.bind(view)

        setupUI()
        observeResults()
    }

    private fun setupUI() {
        // 오늘 날짜 표시
        b.tvDate.text = LocalDate.now()
            .format(DateTimeFormatter.ISO_DATE)

        // 위험군 RecyclerView
        b.rvRisk.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = riskAdapter
        }

        // 비위험군 RecyclerView
        b.rvSafe.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = safeAdapter
        }
    }

    private fun observeResults() {
        lifecycleScope.launch {
            try {
                repo.watchTodayResults(requireContext()).collectLatest { list: List<ChecklistResult> ->
                    handleResultsUpdate(list)
                }
            } catch (e: Exception) {
                showError("데이터 로딩 중 오류가 발생했습니다: ${e.message}")
            }
        }
    }

    private fun handleResultsUpdate(list: List<ChecklistResult>) {
        if (list.isEmpty()) {
            Toast.makeText(requireContext(), "오늘의 결과가 없습니다", Toast.LENGTH_SHORT).show()
            riskAdapter.submitList(emptyList())
            safeAdapter.submitList(emptyList())
            return
        }

        // finalSafetyScore로 분기 (실제 최종 안전 점수 사용)
        val riskList = list.filter { it.finalSafetyScore < SAFETY_THRESHOLD }
        val safeList = list.filter { it.finalSafetyScore >= SAFETY_THRESHOLD }

        riskAdapter.submitList(riskList)
        safeAdapter.submitList(safeList)

        // 통계 정보 표시
        showStatistics(riskList.size, safeList.size)
    }

    private fun showResultDetail(item: ChecklistResult) {
        val message = buildString {
            appendLine("=== 기본 정보 ===")
            appendLine("이름: ${item.name}")
            //appendLine("부서: ${item.dept}")
            appendLine("날짜: ${item.date}")
            appendLine("안전 등급: ${item.safetyLevel}")
            appendLine()

            appendLine("=== 점수 상세 ===")
            appendLine("최종 안전 점수: ${item.finalSafetyScore}점")
            appendLine("체크리스트 점수: ${item.checklistScore}점")
            appendLine()

            appendLine("=== 생체신호 측정 ===")
            appendLine("맥박(PPG) 점수: ${item.ppgScore}점")
            appendLine("동공 측정 점수: ${item.pupilScore}점")
            appendLine("손떨림 측정 점수: ${item.tremorScore}점")

            if (item.recommendations.isNotEmpty()) {
                appendLine()
                appendLine("=== 권장사항 ===")
                item.recommendations.forEach { recommendation ->
                    appendLine("• $recommendation")
                }
            }
        }

        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun showStatistics(riskCount: Int, safeCount: Int) {
        val total = riskCount + safeCount
        val riskPercentage = if (total > 0) (riskCount * 100 / total) else 0

        activity?.title = "위험군: ${riskCount}명 (${riskPercentage}%) | 안전군: ${safeCount}명"
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}