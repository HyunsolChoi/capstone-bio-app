package com.jjangdol.biorhythm.util

import android.content.Context
import android.widget.Toast
import androidx.navigation.NavController
import com.google.firebase.firestore.FirebaseFirestore
import com.jjangdol.biorhythm.R


// firestore에 employees 컬렉션에 업로드 된 직원정보를 조회하는 오브젝트
object LoginUtil {
    fun loginChecker(
        context: Context,
        firestore: FirebaseFirestore,
        name: String,
        empNum: String,
        navController: NavController,
        onLoading: (Boolean) -> Unit
    ) {
        // 이름, 사번 중에 하나라도 입력하지 않은 경우
        if (name.isEmpty()) {
            Toast.makeText(context, "이름을 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (empNum.isEmpty()) {
            Toast.makeText(context, "사번을 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }

        onLoading(true)

        // 입력된 사번, 이름을 컬렉션에 저장된 값과 비교
        firestore.collection("employees")
            .document(empNum)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val savedName = doc.getString("Name")
                    if (savedName == name) {
                        Toast.makeText(context, "로그인 성공!", Toast.LENGTH_SHORT).show()

                        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString("user_name", name)
                            .putString("user_empNum", empNum)
                            .apply()

                        navController.navigate(R.id.action_login_to_main)
                    } else {
                        Toast.makeText(context, "이름이 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
                        onLoading(false)
                    }
                } else {
                    Toast.makeText(context, "해당 사번이 존재하지 않습니다.", Toast.LENGTH_SHORT).show()
                    onLoading(false)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "로그인 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                onLoading(false)
            }
    }
}
