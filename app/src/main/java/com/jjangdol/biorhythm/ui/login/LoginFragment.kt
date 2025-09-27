package com.jjangdol.biorhythm.ui.login

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.firebase.firestore.FirebaseFirestore
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentLoginBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LoginFragment : Fragment(R.layout.fragment_login) {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private val firestore = FirebaseFirestore.getInstance()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentLoginBinding.bind(view)

        // 로그인 버튼 클릭
        binding.btnLogin.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            val empNum = binding.etEmployNum.text.toString().trim()

            performLogin(name, empNum)
        }
    }

    private fun performLogin(name: String, empNum: String) {
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), "이름을 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (empNum.isEmpty()) {
            Toast.makeText(requireContext(), "사번을 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }

        // Firestore에서 조회
        binding.btnLogin.isEnabled = false
        binding.btnLogin.text = "로그인 중…"

        firestore.collection("employees")
            .document(empNum) // 문서 ID = 사번
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val savedName = doc.getString("Name")
                    if (savedName == name) {
                        // ✅ 로그인 성공
                        Toast.makeText(requireContext(), "로그인 성공!", Toast.LENGTH_SHORT).show()

                        // SharedPreferences에 저장 (선택사항)
                        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString("user_name", name)
                            .putString("user_empNum", empNum)
                            .apply()

                        // 메인 화면으로 이동
                        findNavController().navigate(R.id.action_login_to_main)
                    } else {
                        Toast.makeText(requireContext(), "이름이 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
                        binding.btnLogin.isEnabled = true
                        binding.btnLogin.text = getString(R.string.login)
                    }
                } else {
                    Toast.makeText(requireContext(), "해당 사번이 존재하지 않습니다.", Toast.LENGTH_SHORT).show()
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = getString(R.string.login)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "로그인 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.btnLogin.isEnabled = true
                binding.btnLogin.text = getString(R.string.login)
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
