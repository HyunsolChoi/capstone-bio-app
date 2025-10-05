package com.jjangdol.biorhythm.ui.login

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.firebase.functions.FirebaseFunctions
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentLoginBinding

class LoginFragment : Fragment(R.layout.fragment_login) {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private lateinit var functions: FirebaseFunctions

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentLoginBinding.bind(view)
        functions = FirebaseFunctions.getInstance("asia-northeast3")


        binding.btnLogin.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            val empNum = binding.etEmployNum.text.toString().trim()

            Log.d("LoginInput", "입력값 확인 → name=$name / empNum=$empNum")

            if (name.isEmpty() || empNum.isEmpty()) {
                Toast.makeText(requireContext(), "이름과 사번을 입력해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnLogin.isEnabled = false
            binding.btnLogin.text = "로그인 중…"

            val data = hashMapOf(
                "name" to name,
                "empNum" to empNum
            )

            functions
                .getHttpsCallable("loginChecker")
                .call(data)
                .addOnSuccessListener { result ->
                    val res = result.data as Map<*, *>
                    if (res["status"] == "success") {
                        val userName = res["name"] as? String ?: name

                        // 로그인 성공 시 이름을 SharedPreferences에 저장
                        val prefs = requireContext().getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString("user_name", userName)
                            .putString("emp_num", empNum)
                            .apply()

                        Toast.makeText(requireContext(), "로그인 성공!", Toast.LENGTH_SHORT).show()
                        findNavController().navigate(R.id.action_login_to_main)
                    }
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = getString(R.string.login)
                }
                .addOnFailureListener { e ->
                    Toast.makeText(requireContext(), "로그인 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = getString(R.string.login)
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
