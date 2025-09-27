package com.jjangdol.biorhythm.ui.login

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.firebase.firestore.FirebaseFirestore
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.data.UserRepository
import com.jjangdol.biorhythm.databinding.FragmentLoginBinding
import com.jjangdol.biorhythm.vm.LoginState
import com.jjangdol.biorhythm.vm.LoginViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@AndroidEntryPoint
class LoginFragment : Fragment(R.layout.fragment_login) {

    private val vm: LoginViewModel by viewModels()
    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private val dateFormatter = DateTimeFormatter.ISO_DATE

    @Inject
    lateinit var userRepository: UserRepository

    // Firebase Firestore 인스턴스
    private val firestore = FirebaseFirestore.getInstance()

    // 관리자 버튼 클릭 카운트
    private var adminClickCount = 0
    private var lastClickTime = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentLoginBinding.bind(view)


        // 숨겨진 관리자 버튼 클릭 처리
        binding.btnHiddenAdmin.setOnClickListener {
            val currentTime = System.currentTimeMillis()

            // 3초 내에 연속 클릭인지 확인
            if (currentTime - lastClickTime < 3000) {
                adminClickCount++
            } else {
                adminClickCount = 1
            }
            lastClickTime = currentTime

            // 3번 연속 클릭 시 비밀번호 입력 다이얼로그 표시
            if (adminClickCount >= 3) {
                showAdminPasswordDialog()
                adminClickCount = 0
            }
        }

        // 로그인 버튼 클릭
        binding.btnLogin.setOnClickListener {
            val name = binding.etName.text.toString()
            val Enum = binding.etEmployNum.text.toString()

            performLogin(name, Enum)
        }

        // 상태 관찰
        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            vm.state.collectLatest { state ->
                when (state) {
                    is LoginState.Loading -> {
                        binding.btnLogin.isEnabled = false
                        binding.btnLogin.text = "로그인 중…"
                    }
                    is LoginState.Success -> {
                        findNavController().navigate(R.id.action_login_to_main)
                    }
                    is LoginState.Error -> {
                        binding.btnLogin.isEnabled = true
                        binding.btnLogin.text = getString(R.string.login)
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun performLogin(name: String, Enum: String) {
        if (name.trim().isEmpty()) {
            Toast.makeText(requireContext(), "이름을 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (Enum.trim().isEmpty()) {
            Toast.makeText(requireContext(), "사원번호를 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 로딩 상태 설정
                binding.btnLogin.isEnabled = false
                binding.btnLogin.text = "로그인 중…"

                // 1) UserRepository를 통해 사용자 프로필 저장/업데이트
                userRepository.signInAndSaveProfile(name, Enum)

                // 2) SharedPreferences에도 저장 (앱 내에서 빠른 접근용)
                val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("user_name", name)
                    .putString("user_Enum", Enum)
                    .apply()

                // 3) LoginViewModel을 통한 로그인 처리
                vm.login(name, Enum)

            } catch (e: Exception) {
                binding.btnLogin.isEnabled = true
                binding.btnLogin.text = getString(R.string.login)
                Toast.makeText(requireContext(), "로그인 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAdminPasswordDialog() {
        val input = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "관리자 비밀번호를 입력하세요"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("관리자 인증")
            .setMessage("관리자 모드에 접근하려면 비밀번호를 입력하세요.")
            .setView(input)
            .setPositiveButton("확인") { _, _ ->
                val password = input.text.toString()
                checkAdminPassword(password)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // Firebase를 사용한 관리자 비밀번호 확인
    private fun checkAdminPassword(password: String) {
        // 로딩 표시
        Toast.makeText(requireContext(), "인증 중...", Toast.LENGTH_SHORT).show()

        firestore.collection("password")
            .document("admin")
            .get()
            .addOnSuccessListener { document ->
                val savedPassword = document.getString("password") ?: "0000" // 기본 비밀번호

                if (password == savedPassword) {
                    // 비밀번호 일치 시 관리자 모드로 이동
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            findNavController().navigate(R.id.action_login_to_admin)
                        } catch (e: Exception) {
                            // 직접 관리자 Fragment로 이동하는 로직 추가 필요
                            Toast.makeText(requireContext(), "관리자 모드 진입", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "비밀번호가 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { exception ->
                Toast.makeText(requireContext(), "인증 실패: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}