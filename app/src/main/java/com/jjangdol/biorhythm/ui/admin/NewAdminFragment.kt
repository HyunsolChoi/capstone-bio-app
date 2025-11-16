package com.jjangdol.biorhythm.ui.admin

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentNewAdminBinding
import com.jjangdol.biorhythm.data.ResultsRepository
import com.jjangdol.biorhythm.model.ChecklistResult
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@AndroidEntryPoint
class NewAdminFragment : Fragment(R.layout.fragment_new_admin) {

    private var _binding: FragmentNewAdminBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var resultsRepository: ResultsRepository

    // Firebase Firestore 인스턴스
    private val db = FirebaseFirestore.getInstance()

    private lateinit var adminResultsAdapter: AdminResultsAdapter

    private var dataObserverJob: Job? = null

    private var selectedDate: LocalDate = LocalDate.now()
    private var selectedScoreFilter: ScoreFilter = ScoreFilter.ALL
    private var allResults: List<ChecklistResult> = emptyList() // 캐시된 결과


    enum class ScoreFilter {
        ALL, DANGER, CAUTION, SAFE
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentNewAdminBinding.bind(view)

        binding.tvHomeLink.setOnClickListener{
            findNavController().navigate(R.id.action_newAdminFragment_to_main)}



        setupRecyclerView()
        setupClickListeners()
        observeData() // loadData() 대신 observeData() 사용
    }

    private fun setupRecyclerView() {
        adminResultsAdapter = AdminResultsAdapter { result ->
            // 클릭 시 상세 정보 다이얼로그 표시
            showResultDetailDialog(result)
        }

        binding.recyclerViewResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = adminResultsAdapter
        }
    }

    private fun setupClickListeners() {
        // 점수 필터 칩 그룹
        binding.chipGroupScore.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedScoreFilter = when (checkedIds.firstOrNull()) {
                R.id.chipDanger -> ScoreFilter.DANGER
                R.id.chipCaution -> ScoreFilter.CAUTION
                R.id.chipSafe -> ScoreFilter.SAFE
                else -> ScoreFilter.ALL
            }
            applyFilters() // 필터만 다시 적용
        }

        // 날짜 필터 버튼
        binding.btnDateFilter.setOnClickListener {
            showDatePickerDialog()
        }

        // 새로고침 버튼
        binding.btnRefresh.setOnClickListener {
            observeData() // 데이터 다시 로드
            Toast.makeText(requireContext(), "데이터를 새로고침했습니다", Toast.LENGTH_SHORT).show()
        }

        // '일반 페이지로 이동' 버튼
        binding.tvHomeLink.setOnClickListener {
            // 1단계에서 popUpTo를 설정한 action ID 사용
            findNavController().navigate(R.id.action_newAdminFragment_to_main)
        }

        /// 알림 관리 버튼 (수정)
        binding.btnManageNotifications.setOnClickListener {
            // try-catch 제거, NavController만 사용
            findNavController().navigate(R.id.action_admin_to_notification_management)
        }

        // 비밀번호 변경 버튼
        binding.btnChangePassword.setOnClickListener {
            showChangePasswordDialog()
        }

        // 체크리스트 문항 관리 버튼 (수정)
        binding.btnManageChecklist.setOnClickListener {
            // try-catch 제거, NavController만 사용
            findNavController().navigate(R.id.action_newAdminFragment_to_adminChecklistManagementFragment)
        }

        //  관리자 임명 버튼
        binding.btnGrantAdmin.setOnClickListener {
            showGrantAdminDialog()
        }
    }

    private fun observeData() {
        // 이전 Job 취소
        dataObserverJob?.cancel()

        dataObserverJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    // 선택된 날짜에 따라 다른 메서드 사용
                    resultsRepository.watchResultsByDate(selectedDate, requireContext()).collectLatest { results ->
                        // Fragment가 여전히 활성 상태인지 확인
                        if (isAdded && _binding != null && !requireActivity().isFinishing) {
                            allResults = results
                            applyFilters()
                        }
                    }
                } catch (e: CancellationException) {
                    // Job이 취소된 경우는 정상적인 상황이므로 무시
                    android.util.Log.d("NewAdminFragment", "Data observation cancelled")
                } catch (e: Exception) {
                    // 실제 에러인 경우만 처리
                    if (isAdded && _binding != null && !requireActivity().isFinishing) {
                        Toast.makeText(requireContext(), "데이터 로딩 실패: ${e.message}", Toast.LENGTH_LONG).show()
                        // 에러 시 빈 상태 표시
                        allResults = emptyList()
                        applyFilters()
                    }
                }
            }
        }
    }

    private fun applyFilters() {
        // Fragment가 여전히 활성 상태인지 확인
        if (!isAdded || _binding == null) return

        val filteredResults = when (selectedScoreFilter) {
            ScoreFilter.DANGER -> allResults.filter { it.finalSafetyScore < 50 }
            ScoreFilter.CAUTION -> allResults.filter { it.finalSafetyScore in 50..69 }
            ScoreFilter.SAFE -> allResults.filter { it.finalSafetyScore >= 70 }
            ScoreFilter.ALL -> allResults
        }

        // 통계 업데이트 (전체 결과 기준)
        updateStatistics(allResults)

        // 리스트 업데이트 (ChecklistResult 타입으로)
        adminResultsAdapter.submitList(filteredResults)
        binding.tvResultCount.text = "총 ${filteredResults.size}건 (${selectedDate.format(DateTimeFormatter.ofPattern("MM-dd"))})"

        // 빈 상태 처리
        binding.emptyLayout.visibility =
            if (filteredResults.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateStatistics(results: List<ChecklistResult>) {
        // Fragment가 여전히 활성 상태인지 확인
        if (!isAdded || _binding == null) return

        val dangerCount = results.count { it.finalSafetyScore < 50 }
        val cautionCount = results.count { it.finalSafetyScore in 50..69 }
        val safeCount = results.count { it.finalSafetyScore >= 70 }

        binding.tvDangerCount.text = dangerCount.toString()
        binding.tvCautionCount.text = cautionCount.toString()
        binding.tvSafeCount.text = safeCount.toString()
    }

    private fun showDatePickerDialog() {
        val picker = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                binding.btnDateFilter.text = selectedDate.format(DateTimeFormatter.ofPattern("MM-dd"))

                // 날짜 변경 시 새로운 데이터 로드
                observeData()
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth
        )
        picker.show()
    }

    private fun showResultDetailDialog(result: ChecklistResult) {
        val message = buildString {
            appendLine("=== 기본 정보 ===")
            appendLine("이름: ${result.name}")
            //appendLine("부서: ${result.dept}")
            appendLine("날짜: ${result.date}")
            appendLine()

            appendLine("=== 점수 상세 ===")
            appendLine("최종 안전 점수: ${result.finalSafetyScore}점")
            appendLine("체크리스트 점수: ${result.checklistScore}점")
            appendLine()

            appendLine("=== 생체신호 측정 ===")
            appendLine("맥박(PPG) 점수: ${result.ppgScore}점")
            appendLine("동공 측정 점수: ${result.pupilScore}점")
            appendLine("손떨림 측정 점수: ${result.tremorScore}점")

            if (result.timestamp != 0L) {
                appendLine()
                appendLine("측정 시간: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(result.timestamp))}")
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("측정 결과 상세")
            .setMessage(message)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun showChangePasswordDialog() {
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val currentPasswordEdit = EditText(requireContext()).apply {
            hint = "현재 비밀번호"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val newPasswordEdit = EditText(requireContext()).apply {
            hint = "새 비밀번호"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val confirmPasswordEdit = EditText(requireContext()).apply {
            hint = "새 비밀번호 확인"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        layout.addView(currentPasswordEdit)
        layout.addView(newPasswordEdit)
        layout.addView(confirmPasswordEdit)

        AlertDialog.Builder(requireContext())
            .setTitle("비밀번호 변경")
            .setView(layout)
            .setPositiveButton("변경") { _, _ ->
                val currentPassword = currentPasswordEdit.text.toString()
                val newPassword = newPasswordEdit.text.toString()
                val confirmPassword = confirmPasswordEdit.text.toString()

                changePassword(currentPassword, newPassword, confirmPassword)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // Firebase를 사용한 비밀번호 확인
    private fun checkCurrentPassword(inputPassword: String, callback: (Boolean) -> Unit) {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val empNum = prefs.getString("emp_num", null)

        if (empNum.isNullOrEmpty()) {
            return
        }

        db.collection("employees")
            .document(empNum)
            .get()
            .addOnSuccessListener { document ->
                val savedPassword = document.getString("Password") ?: ""
                callback(inputPassword == savedPassword)
            }
            .addOnFailureListener { exception ->
                if (isAdded && _binding != null && !requireActivity().isFinishing) {
                    Toast.makeText(requireContext(), "비밀번호 확인 실패: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
                callback(false)
            }
    }

    // Firebase를 사용한 비밀번호 업데이트
    private fun updatePasswordInFirebase(newPassword: String) {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val empNum = prefs.getString("emp_num", null)
        val passwordData = mapOf("Password" to newPassword)

        if (empNum.isNullOrEmpty()) {
            return
        }

        db.collection("employees")
            .document(empNum)
            .set(passwordData, SetOptions.merge())
            .addOnSuccessListener {
                if (isAdded && _binding != null && !requireActivity().isFinishing) {
                    Toast.makeText(requireContext(), "비밀번호가 성공적으로 변경되었습니다", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { exception ->
                if (isAdded && _binding != null && !requireActivity().isFinishing) {
                    Toast.makeText(requireContext(), "비밀번호 변경 실패: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun changePassword(current: String, new: String, confirm: String) {
        when {
            new.length < 4 -> {
                Toast.makeText(requireContext(), "새 비밀번호는 4자 이상이어야 합니다", Toast.LENGTH_SHORT).show()
            }
            new != confirm -> {
                Toast.makeText(requireContext(), "새 비밀번호가 일치하지 않습니다", Toast.LENGTH_SHORT).show()
            }
            else -> {
                // Firebase에서 현재 비밀번호 확인
                checkCurrentPassword(current) { isValid ->
                    if (isValid) {
                        // 현재 비밀번호가 맞으면 새 비밀번호로 업데이트
                        updatePasswordInFirebase(new)
                    } else {
                        if (isAdded && _binding != null && !requireActivity().isFinishing) {
                            Toast.makeText(requireContext(), "현재 비밀번호가 올바르지 않습니다", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun showGrantAdminDialog() {
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val empNumEdit = EditText(requireContext()).apply {
            hint = "사원번호"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val nameEdit = EditText(requireContext()).apply {
            hint = "이름"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val authEdit = EditText(requireContext()).apply {
            hint = "권한 (0: 최고관리자, 1: 일반관리자)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val passwordEdit = EditText(requireContext()).apply {
            hint = "새 비밀번호"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        layout.addView(empNumEdit)
        layout.addView(nameEdit)
        layout.addView(authEdit)
        layout.addView(passwordEdit)

        AlertDialog.Builder(requireContext())
            .setTitle("관리자 임명")
            .setView(layout)
            .setPositiveButton("부여", null) // null로 설정
            .setNegativeButton("취소", null)
            .create()

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("관리자 권한 부여")
            .setView(layout)
            .setPositiveButton("부여", null) // null로 설정
            .setNegativeButton("취소", null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val empNum = empNumEdit.text.toString()
                val name = nameEdit.text.toString()
                val authInput = authEdit.text.toString()
                val password = passwordEdit.text.toString()

                grantAdminPermission(empNum, name, authInput, password, dialog)
            }
        }

        dialog.show()
    }

    private fun grantAdminPermission(empNum: String, name: String, authInput: String, password: String, dialog: AlertDialog) {
        // 입력 유효성 검사
        when {
            empNum.isEmpty() -> {
                Toast.makeText(requireContext(), "사원번호를 입력해주세요", Toast.LENGTH_SHORT).show()
                return
            }
            name.isEmpty() -> {
                Toast.makeText(requireContext(), "이름을 입력해주세요", Toast.LENGTH_SHORT).show()
                return
            }
            authInput.isEmpty() -> {
                Toast.makeText(requireContext(), "권한을 입력해주세요", Toast.LENGTH_SHORT).show()
                return
            }
            password.length < 4 -> {
                Toast.makeText(requireContext(), "비밀번호는 4자 이상이어야 합니다", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val newAuth = authInput.toIntOrNull()
        if (newAuth == null || (newAuth != 0 && newAuth != 1)) {
            Toast.makeText(requireContext(), "권한은 0 또는 1만 가능합니다", Toast.LENGTH_SHORT).show()
            return
        }

        // 내 auth 읽어오기
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val myEmpNum = prefs.getString("emp_num", null)

        if (myEmpNum.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "로그인 정보를 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        db.collection("employees")
            .document(myEmpNum)
            .get()
            .addOnSuccessListener { myDocument ->
                val myAuth = myDocument.getLong("auth")?.toInt() ?: 1

                // 권한 검증
                if (myAuth == 1 && newAuth == 0) {
                    Toast.makeText(requireContext(), "일반관리자는 최고관리자 권한을 부여할 수 없습니다", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                // 대상 사원 정보 조회
                db.collection("employees")
                    .document(empNum)
                    .get()
                    .addOnSuccessListener { targetDocument ->
                        if (!targetDocument.exists()) {
                            Toast.makeText(requireContext(), "해당 사원번호가 존재하지 않습니다", Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }

                        val targetName = targetDocument.getString("Name") ?: ""
                        val targetAuth = targetDocument.getLong("auth")?.toInt() ?: 2

                        // 이름 확인
                        if (targetName != name) {
                            Toast.makeText(requireContext(), "사원번호와 이름이 일치하지 않습니다", Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }

                        // 내 auth가 1인데 상대방의 auth가 0인 경우
                        if (myAuth == 1 && targetAuth == 0) {
                            Toast.makeText(requireContext(), "일반관리자는 최고관리자의 권한을 변경할 수 없습니다", Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }

                        // 이미 같은 권한을 가진 경우
                        if (targetAuth == newAuth) {
                            val authName = if (newAuth == 0) "최고관리자" else "일반관리자"
                            Toast.makeText(requireContext(), "이미 ${authName} 권한을 보유하고 있습니다", Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }

                        // 권한 및 비밀번호 업데이트
                        val updateData = mapOf(
                            "auth" to newAuth,
                            "Password" to password
                        )

                        db.collection("employees")
                            .document(empNum)
                            .set(updateData, SetOptions.merge())
                            .addOnSuccessListener {
                                val authName = if (newAuth == 0) "최고관리자" else "일반관리자"
                                Toast.makeText(requireContext(), "${name}님에게 ${authName} 권한이 부여되었습니다", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                            }
                            .addOnFailureListener { exception ->
                                Toast.makeText(requireContext(), "권한 부여 실패: ${exception.message}", Toast.LENGTH_SHORT).show()
                            }
                    }
                    .addOnFailureListener { exception ->
                        Toast.makeText(requireContext(), "사원 정보 조회 실패: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener { exception ->
                Toast.makeText(requireContext(), "내 권한 정보 조회 실패: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onDestroy() {
        // Job 명시적으로 취소
        dataObserverJob?.cancel()
        dataObserverJob = null
        super.onDestroy()
    }

    override fun onDestroyView() {
        // Job 명시적으로 취소
        dataObserverJob?.cancel()
        dataObserverJob = null

        super.onDestroyView()
        _binding = null
    }
}