package com.jjangdol.biorhythm.ui.login


import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.firebase.firestore.FirebaseFirestore
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentLoginBinding
import dagger.hilt.android.AndroidEntryPoint
import com.jjangdol.biorhythm.util.LoginUtil

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

            // firestore에서 사용자 정보를 가져와서 입력된 정보와 비교
            LoginUtil.loginChecker(
                context = requireContext(),
                firestore = firestore,
                name = name,
                empNum = empNum,
                navController = findNavController()
            ) { isLoading ->
                binding.btnLogin.isEnabled = !isLoading
                binding.btnLogin.text = if (isLoading) "로그인 중…" else getString(R.string.login)
            }
        }
    }



    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
