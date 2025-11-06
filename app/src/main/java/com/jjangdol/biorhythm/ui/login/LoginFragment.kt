package com.jjangdol.biorhythm.ui.login

import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.ktx.Firebase
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentLoginBinding
import java.security.MessageDigest

class LoginFragment : Fragment(R.layout.fragment_login) {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private lateinit var functions: FirebaseFunctions
    private val db = Firebase.firestore

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

            functions.getHttpsCallable("loginChecker")
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

                        // Android ID 가져오기
                        val androidId = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)

                        // firestore에 저장 시에는 해쉬된 값을 저장하도록 함.
                        fun sha256(input: String): String {
                            val md = MessageDigest.getInstance("SHA-256")
                            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
                            return digest.joinToString("") { "%02x".format(it) }
                        }

                        val androidIdHash = sha256(androidId)

                        val currentDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                        val currentTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

                        // firestore에 저장 (현재 시간과 사번을 기준으로 하여 필드명을 고유하게 하고 그 안에 안드로이드 id가 남게함)
                        db.collection("LoginHistory")
                            .document(currentDate)
                            .set(mapOf("$currentTime $empNum" to androidIdHash), SetOptions.merge())
                            .addOnSuccessListener {
                                Log.d("LoginHistory", "로그인 기록 저장 완료")
                            }
                            .addOnFailureListener { e ->
                                Log.e("LoginHistory", "로그인 기록 저장 실패", e)
                            }

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
