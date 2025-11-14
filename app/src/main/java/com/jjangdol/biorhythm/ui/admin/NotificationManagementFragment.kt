// app/src/main/java/com/jjangdol/biorhythm/ui/admin/NotificationManagementFragment.kt
package com.jjangdol.biorhythm.ui.admin

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentNotificationManagementBinding
import com.jjangdol.biorhythm.data.model.Notification
import com.jjangdol.biorhythm.data.model.NotificationPriority
import com.jjangdol.biorhythm.vm.NotificationManagementViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.LinearLayout
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import android.widget.ArrayAdapter
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import android.util.Log
import android.content.Context

@AndroidEntryPoint
class NotificationManagementFragment : Fragment(R.layout.fragment_notification_management) {

    private var _binding: FragmentNotificationManagementBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NotificationManagementViewModel by viewModels()
    private lateinit var notificationAdapter: NotificationAdapter

    private var selectedPriority = NotificationPriority.NORMAL
    private var filterPriority: NotificationPriority? = null

    private val selectedAttachmentUris = mutableListOf<Uri>()
    private val editAttachmentUris = mutableListOf<Uri>()
    private companion object { const val DEPT_ALL = "전체" }
    private val selectedDeptPathGlobal: MutableList<String> = mutableListOf()

    private val openDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments())
    { uris ->
        if (!uris.isNullOrEmpty()) {
            addAttachments(uris)
        }
    }

    private val openDocumentsForEdit = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments())
    { uris ->
        if (!uris.isNullOrEmpty()) {
            uris.forEach { uri ->
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                editAttachmentUris.add(uri)
            }
            // 콜백으로 UI 업데이트
            currentAttachmentStatusCallback?.invoke()
        }
    }

    private var currentAttachmentStatusCallback: (() -> Unit)? = null
    private var currentReceiverStatusCallback: (() -> Unit)? = null
    private var selectAuth: MutableSet<String> = mutableSetOf()
    private var selectDept: MutableSet<String> = mutableSetOf()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        FirebaseFirestore.setLoggingEnabled(true)
        _binding = FragmentNotificationManagementBinding.bind(view)

        setupUI()
        setupRecyclerView()
        setupClickListeners()
        observeViewModel()

        val currentUserEmpNum = getUserEmpNum()
        if (currentUserEmpNum != null) {
            viewModel.loadCurrentUserDepartment(currentUserEmpNum)
        }

        val db = FirebaseFirestore.getInstance()
        val collectionRef = db.collection("Department")

        collectionRef.get()
            .addOnSuccessListener { querySnapshot ->
                val documentIds = querySnapshot.documents.map { it.id }
            }
            .addOnFailureListener { e ->
                Log.e("Firestore", "Department 문서 조회 실패", e)
            }
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun getEmployeeIdFromPrefs(): String? {
        val sp = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        return sp.getString("emp_num", null)
    }

    private fun setupRecyclerView() {
        notificationAdapter = NotificationAdapter(
            onItemClick = { notification ->
                showNotificationDetailDialog(notification)
            },
            onEditClick = { notification ->
                showEditNotificationDialog(notification)
            },
            onDeleteClick = { notification ->
                showDeleteConfirmDialog(notification)
            },
            onToggleStatus = { notification ->
                viewModel.toggleNotificationStatus(notification.id)
            }
        )

        binding.recyclerViewNotifications.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = notificationAdapter
        }
    }

    private fun setupClickListeners() {
        // 우선순위 선택
        binding.chipGroupPriority.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedPriority = when (checkedIds.firstOrNull()) {
                R.id.chipHigh -> NotificationPriority.HIGH
                R.id.chipLow -> NotificationPriority.LOW
                else -> NotificationPriority.NORMAL
            }
        }

        // 파일첨부 버튼
        binding.btnAddFile.setOnClickListener {
            // MIME 타입 원하는 대로 제한 가능: image/*, application/pdf 등
            openDocuments.launch(arrayOf("image/*", "application/pdf"))
        }

        // 필터 선택
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            filterPriority = when (checkedIds.firstOrNull()) {
                R.id.chipFilterHigh -> NotificationPriority.HIGH
                R.id.chipFilterNormal -> NotificationPriority.NORMAL
                R.id.chipFilterLow -> NotificationPriority.LOW
                else -> null
            }
            viewModel.setFilter(filterPriority)
        }

        // 알림 등록 버튼
        binding.btnCreateNotification.setOnClickListener {
            createNotification()
        }

        // 수신자 그룹 버튼
        binding.btnReceiver.setOnClickListener { openReceiverDropdownDialog() }

        // 새로고침 버튼
        binding.btnRefreshNotifications.setOnClickListener {
            viewModel.refreshNotifications()
            Toast.makeText(requireContext(), "알림 목록을 새로고침했습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.notifications.collectLatest { notifications ->
                notificationAdapter.submitList(notifications)
                binding.tvNotificationCount.text = "총 ${notifications.size}건"

                // 빈 상태 처리
                binding.emptyNotificationLayout.visibility =
                    if (notifications.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is NotificationManagementViewModel.UiState.Loading -> {
                        binding.btnCreateNotification.isEnabled = false
                        binding.btnCreateNotification.text = "등록 중..."
                    }
                    is NotificationManagementViewModel.UiState.Success -> {
                        binding.btnCreateNotification.isEnabled = true
                        binding.btnCreateNotification.text = "알림 등록"
                        clearForm()
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                    }
                    is NotificationManagementViewModel.UiState.Error -> {
                        binding.btnCreateNotification.isEnabled = true
                        binding.btnCreateNotification.text = "알림 등록"
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        binding.btnCreateNotification.isEnabled = true
                        binding.btnCreateNotification.text = "알림 등록"
                    }
                }
            }
        }
    }

    private fun createNotification() {
        val title = binding.etNotificationTitle.text?.toString()?.trim() ?: ""
        val content = binding.etNotificationContent.text?.toString()?.trim() ?: ""

        val auth: Int = (selectAuth.firstOrNull()?.toIntOrNull() ?: 2)

        when {
            title.isEmpty() -> {
                binding.etNotificationTitle.error = "제목을 입력하세요"
                binding.etNotificationTitle.requestFocus()
            }
            content.isEmpty() -> {
                binding.etNotificationContent.error = "내용을 입력하세요"
                binding.etNotificationContent.requestFocus()
            }
            else -> {
                val deptTargets =
                    if (selectDept.contains(DEPT_ALL)) listOf(DEPT_ALL)
                    else buildDeptTargetsFromPath(selectedDeptPathGlobal)

                viewModel.createNotification(title, content, selectedPriority, attachments = selectedAttachmentUris.toList(), auth, targetDept = deptTargets)
            }
        }
    }

    //dept 묶어서 가져옴
    private fun buildDeptTargetsFromPath(parts: List<String>): List<String> {
        return parts.indices.map { i -> parts.take(i + 1).joinToString("/") }
    }

    //수신자 그룹 선택
    private fun openReceiverDropdownDialog() {
        val ctx = requireContext()
        val db = FirebaseFirestore.getInstance()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        fun makeDropdown(label: String, hintText: String): Pair<TextInputLayout, MaterialAutoCompleteTextView> {
            val til = TextInputLayout(ctx, null, com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox_ExposedDropdownMenu).apply {
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                setPadding(0, 16, 0, 0)
                this.hint = label
            }
            val actv = MaterialAutoCompleteTextView(til.context).apply {
                isFocusable = false
                isCursorVisible = false
                keyListener = null
                setText("", false)
                this.hint = hintText
            }
            til.addView(actv)
            root.addView(til)
            return til to actv
        }

        // 드롭다운
        val (_, ddAuth) = makeDropdown("수신자 권한", "")
        val (_, targetDept1) = makeDropdown("수신자 부서", "")
        val (hideDept2, targetDept2) = makeDropdown("2단계 부서", "")
        val (hideDept3, targetDept3) = makeDropdown("3단계 부서", "")
        val (hideDept4, targetDept4) = makeDropdown("4단계 부서", "")
        hideDept2.visibility = View.GONE
        hideDept3.visibility = View.GONE
        hideDept4.visibility = View.GONE

        val selectedDeptPath = mutableListOf<String>()

        // 현재 선택값 UI 반영
        fun refreshTexts() {
            ddAuth.setText(selectAuth.firstOrNull() ?: "", false)
            targetDept1.setText(selectDept.firstOrNull() ?: "", false)
        }
        refreshTexts()

        viewLifecycleOwner.lifecycleScope.launch {
            // 작성자 auth 가져오기
            val senderAuth = try {
                val empNum = getEmployeeIdFromPrefs()
                if (empNum.isNullOrBlank()) {
                    Toast.makeText(ctx, "사번 정보를 찾을 수 없습니다. 다시 로그인해주세요.", Toast.LENGTH_LONG).show()
                    null
                } else {
                    val emp = db.collection("employees").document(empNum).get().await()
                    val raw = emp.get("auth")

                    when (raw) {
                        is String -> raw               // "0" | "1"
                        is Number -> raw.toInt().toString()
                        is Boolean -> if (raw) "1" else "0"
                        else -> null
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "프로필 로딩 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                null
            }

            // 드롭다운 항목
            val authItems = when (senderAuth) {
                "0" -> listOf("0", "1", DEPT_ALL)
                "1" -> listOf("1", DEPT_ALL)
                else -> emptyList()
            }
            ddAuth.setAdapter(ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, authItems))
            ddAuth.setOnClickListener {
                if (authItems.isEmpty())
                {
                    Toast.makeText(ctx, "발송 권한이 없습니다", Toast.LENGTH_SHORT).show(); return@setOnClickListener
                }
                ddAuth.showDropDown()
            }
            ddAuth.setOnItemClickListener { _, _, position, _ ->
                val v = authItems[position]
                if (senderAuth == "1" && v == "0")
                {
                    Toast.makeText(ctx, "관리자(1)는 0에게 보낼 수 없습니다", Toast.LENGTH_SHORT).show()
                    refreshTexts()
                    return@setOnItemClickListener
                }
                selectAuth.clear()
                selectAuth.add(v)
            }

            val topLevelDepts = loadTopLevelDepts(db)
            val dept1Items = listOf(DEPT_ALL) + topLevelDepts
            targetDept1.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, dept1Items))
            targetDept1.setOnClickListener {
                if (dept1Items.isEmpty()) {
                    Toast.makeText(ctx, "부서 목록을 불러오는 중", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                targetDept1.showDropDown()
            }
            targetDept1.setOnItemClickListener { _, _, position, _ ->
                val selected = dept1Items[position]

                selectedDeptPath.clear()
                targetDept2.setText("", false)
                targetDept3.setText("", false)
                targetDept4.setText("", false)
                hideDept2.visibility = View.GONE
                hideDept3.visibility = View.GONE
                hideDept4.visibility = View.GONE

                if (selected == DEPT_ALL) {
                    selectDept.clear()
                    selectDept.add(DEPT_ALL)
                } else {
                    selectedDeptPath.add(selected)
                    loadNextLevel(db, selectedDeptPath, hideDept2, targetDept2, hideDept3, targetDept3, hideDept4, targetDept4)
                }
            }

            targetDept2.setOnItemClickListener { _, _, position, _ ->
                val selected = (targetDept2.adapter.getItem(position) as? String) ?: return@setOnItemClickListener

                while (selectedDeptPath.size > 1) selectedDeptPath.removeAt(selectedDeptPath.size - 1)
                targetDept3.setText("", false)
                targetDept4.setText("", false)
                hideDept3.visibility = View.GONE
                hideDept4.visibility = View.GONE

                if (selected.startsWith("전체")) {
                    selectDept.clear()
                    selectDept.add(selectedDeptPath.last())
                } else {
                    selectedDeptPath.add(selected)
                    loadNextLevel(db, selectedDeptPath, hideDept3, targetDept3, hideDept4, targetDept4)
                }
            }

            targetDept3.setOnItemClickListener { _, _, position, _ ->
                val selected = (targetDept3.adapter.getItem(position) as? String) ?: return@setOnItemClickListener

                // 4단계 초기화
                while (selectedDeptPath.size > 2) selectedDeptPath.removeAt(selectedDeptPath.size - 1)
                targetDept4.setText("", false)
                hideDept4.visibility = View.GONE

                if (selected.startsWith("전체")) {
                    selectDept.clear()
                    selectDept.add(selectedDeptPath.last())
                } else {
                    selectedDeptPath.add(selected)
                    loadNextLevel(db, selectedDeptPath, hideDept4, targetDept4)
                }
            }

            targetDept4.setOnItemClickListener { _, _, position, _ ->
                val selected = (targetDept4.adapter.getItem(position) as? String) ?: return@setOnItemClickListener

                while (selectedDeptPath.size > 3) selectedDeptPath.removeAt(selectedDeptPath.size - 1)

                if (selected.startsWith("전체")) {
                    selectDept.clear()
                    selectDept.add(selectedDeptPath.last())
                } else {
                    selectedDeptPath.add(selected)
                    selectDept.clear()
                    selectDept.add(selected)
                }
            }

            // 다이얼로그 표시
            AlertDialog.Builder(ctx)
                .setTitle("수신자 그룹 선택")
                .setView(root)
                .setNegativeButton("닫기", null)
                .setPositiveButton("확인") { d, _ ->
                    if (senderAuth == null) {
                        Toast.makeText(ctx, "발송 권한이 없습니다", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (selectAuth.isEmpty()) {
                        Toast.makeText(ctx, "수신자 권한(auth)을 선택하세요", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (selectDept.isEmpty()) {
                        Toast.makeText(ctx, "수신자 부서(dept)를 선택하세요", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (senderAuth == "1" && selectAuth.contains("0")) {
                        Toast.makeText(ctx, "관리자(1)는 0에게 보낼 수 없습니다", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    selectedDeptPathGlobal.clear()
                    if (!selectDept.contains(DEPT_ALL)) {
                        selectedDeptPathGlobal.addAll(selectedDeptPath)
                    }
                    currentReceiverStatusCallback?.invoke()
                    d.dismiss()
                }
                .show()
        }
    }

    //하위부서 표시
    private fun loadNextLevel(
        db: FirebaseFirestore,
        currentPath: List<String>,
        vararg dropdownPairs: Any
    ) {
        val tilNext = dropdownPairs[0] as TextInputLayout
        val ddNext = dropdownPairs[1] as MaterialAutoCompleteTextView

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val parentDeptId = currentPath.last()
                val subDepts = loadSubDepartments(db, currentPath)

                if (subDepts.isEmpty()) {
                    // 하위 부서가 없으면 현재 선택으로 확정
                    tilNext.visibility = View.GONE
                    selectDept.clear()
                    selectDept.add(parentDeptId)
                    Toast.makeText(requireContext(), "$parentDeptId 선택됨 (하위 부서 없음)", Toast.LENGTH_SHORT).show()
                } else {
                    // 하위 부서가 있으면 드롭다운 표시
                    tilNext.visibility = View.VISIBLE
                    val items = listOf("전체 ($parentDeptId 포함)") + subDepts

                    ddNext.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, items))
                    ddNext.setOnClickListener {
                        ddNext.showDropDown()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "하위 부서 로딩 실패", Toast.LENGTH_SHORT).show()
                tilNext.visibility = View.GONE
            }
        }
    }

    //하위부서 가져오기
    private suspend fun loadSubDepartments(db: FirebaseFirestore, parentPath: List<String>): List<String> {
        if (parentPath.isEmpty()) return emptyList()

        val subCollectionNames = listOf("Level1","Level2","Level3")
        var docRef = db.collection("Department").document(parentPath[0])

        for (i in 1 until parentPath.size) {
            val subCollName = subCollectionNames.getOrElse(i - 1) { "Department" }
            docRef = docRef.collection(subCollName).document(parentPath[i])
        }

        val nextSubCollName = subCollectionNames.getOrElse(parentPath.size - 1) { "Department" }
        val snapshot = docRef.collection(nextSubCollName).get().await()

        return snapshot.documents.mapNotNull { it.getString("name") ?: it.getString("korName") ?: it.getString("title") ?: it.id }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    //최상위부서 가져오기
    private suspend fun loadTopLevelDepts(db: FirebaseFirestore): List<String> {
        try {
            val snap = db.collection("Department").get().await()

            if (!snap.isEmpty) {
                val deptList = snap.documents.map { doc ->
                    val name = doc.getString("name")
                        ?: doc.getString("korName")
                        ?: doc.getString("title")
                        ?: doc.id

                    name
                }.filter { it.isNotBlank() }
                    .distinct()
                    .sorted()

                return deptList
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "부서 목록을 불러오지 못했습니다.", Toast.LENGTH_LONG).show()
        }

        return emptyList()
    }

    private fun addAttachments(newUris: List<Uri>) {
        // 권한
        newUris.forEach { uri -> requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        // 중복 제거 후 추가
        val current = selectedAttachmentUris.map { it.toString() }.toMutableSet()
        newUris.forEach { uri ->
            if (current.add(uri.toString())) { selectedAttachmentUris.add(uri) }
        }

        // UI 갱신 (버튼 텍스트/칩/리사이클러 등)
        binding.btnAddFile.text =
            if (selectedAttachmentUris.isEmpty()) "첨부파일" else "첨부파일 (${selectedAttachmentUris.size})"
        binding.tvAttachmentStatus.text =
            if (selectedAttachmentUris.isEmpty()) "첨부된 파일이 없습니다"
            else "${selectedAttachmentUris.size}개의 파일이 첨부되었습니다."
    }

    private fun clearForm() {
        binding.etNotificationTitle.text?.clear()
        binding.etNotificationContent.text?.clear()
        binding.chipGroupPriority.check(R.id.chipNormal)
        selectedPriority = NotificationPriority.NORMAL
        selectedAttachmentUris.clear()
    }

    private fun showNotificationDetailDialog(notification: Notification) {
        val displayMetrics = resources.displayMetrics
        val maxHeight = (displayMetrics.heightPixels * 0.75).toInt()

        val scrollView = android.widget.ScrollView(requireContext()).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                maxHeight
            )
            setPadding(40, 20, 40, 20)
            isScrollbarFadingEnabled = false
            isVerticalScrollBarEnabled = true
            setBackgroundColor(requireContext().getColor(android.R.color.white))
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        scrollView.addView(container)

        // 알림 정보 카드
        val infoCard = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 20)
            }
            radius = 12f
            cardElevation = 4f
            setCardBackgroundColor(requireContext().getColor(android.R.color.white))
            strokeWidth = 0
        }

        val infoLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
        }

        // 제목
        val titleText = android.widget.TextView(requireContext()).apply {
            text = notification.title
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(requireContext().getColor(android.R.color.black))
            setPadding(0, 0, 0, 16)
            setTextIsSelectable(true)
        }
        infoLayout.addView(titleText)

        // 얇은 구분선
        val topDivider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            setBackgroundColor(requireContext().getColor(android.R.color.darker_gray))
            alpha = 0.2f
        }
        infoLayout.addView(topDivider)

        // 내용
        val contentText = android.widget.TextView(requireContext()).apply {
            text = notification.content
            textSize = 17f
            setTextColor(requireContext().getColor(android.R.color.black))
            setPadding(0, 0, 0, 16)
            setLineSpacing(6f, 1f)
            setTextIsSelectable(true)
        }
        infoLayout.addView(contentText)

        // 메타 정보
        val metaText = android.widget.TextView(requireContext()).apply {
            val formattedDate = notification.createdAt?.toDate()?.let { date ->
                java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.KOREAN).format(date)
            } ?: "알 수 없음"

            text = buildString {
                append("📅 $formattedDate  ")
                append("•  ${if (notification.active) "✓ 활성" else "✗ 비활성"}  ")
                append("•  ${notification.priority.displayName}")
                /** 2는 전체 권한을 의미, 개발 시 혼동 주의! */
                notification.auth?.let {
                    val authText = when(it) {
                        2 -> "전체"
                        else -> it.toString()
                    }
                    append("\n🔐 권한: $authText")
                }
                notification.targetDept?.let {
                    val deptText = if (it.size > 2) {
                        "${it.take(2).joinToString(", ")} 외 ${it.size - 2}개"
                    } else {
                        it.joinToString(", ")
                    }
                    append("  •  🏢 $deptText")
                }
                notification.attachmentUrl?.let {
                    if (it.isNotEmpty()) append("  •  📎 ${it.size}개")
                }
            }
            textSize = 14f
            setTextColor(requireContext().getColor(android.R.color.darker_gray))
            setLineSpacing(4f, 1f)
        }
        infoLayout.addView(metaText)

        infoCard.addView(infoLayout)
        container.addView(infoCard)

        // 읽지 않은 사용자 카드
        val unreadCard = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            radius = 12f
            cardElevation = 4f
            setCardBackgroundColor(requireContext().getColor(android.R.color.white))
            strokeWidth = 0
        }

        val unreadLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
        }

        // 헤더 레이아웃
        val headerLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }

        val unreadTitle = android.widget.TextView(requireContext()).apply {
            text = "👥 읽지 않은 사용자"
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(requireContext().getColor(android.R.color.black))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        headerLayout.addView(unreadTitle)

        // 카운트 뱃지
        val countBadge = android.widget.TextView(requireContext()).apply {
            text = "0"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(requireContext().getColor(android.R.color.white))
            setPadding(16, 6, 16, 6)
            setBackgroundColor(requireContext().getColor(android.R.color.holo_red_light))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(requireContext().getColor(android.R.color.holo_red_light))
                cornerRadius = 20f
            }
            visibility = View.GONE
        }
        headerLayout.addView(countBadge)

        unreadLayout.addView(headerLayout)

        val unreadUsersText = android.widget.TextView(requireContext()).apply {
            text = "불러오는 중..."
            textSize = 15f
            setTextColor(requireContext().getColor(android.R.color.darker_gray))
            setLineSpacing(8f, 1f)
            maxLines = Int.MAX_VALUE  // 여러 줄 허용하되
            ellipsize = android.text.TextUtils.TruncateAt.END  // 각 줄이 넘치면 ...
            setSingleLine(false)  // 여러 줄 가능
        }
        unreadLayout.addView(unreadUsersText)

        unreadCard.addView(unreadLayout)
        container.addView(unreadCard)

        val currentUserEmpNum = getUserEmpNum()
        if (currentUserEmpNum == null) {
            unreadUsersText.text = "⚠️ 사용자 정보를 찾을 수 없습니다"
            AlertDialog.Builder(requireContext())
                .setTitle("${notification.priority.displayName} 알림")
                .setView(scrollView)
                .setPositiveButton("확인", null)
                .show()
            return
        }

        viewModel.loadUnreadUsers(
            notification.id,
            notification.auth,
            notification.targetDept,
            currentUserEmpNum
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.unreadUsersState.collectLatest { state ->
                when (state) {
                    is NotificationManagementViewModel.UnreadUsersState.Initial -> {
                        unreadUsersText.text = "불러오는 중..."
                        countBadge.visibility = View.GONE
                    }
                    is NotificationManagementViewModel.UnreadUsersState.Loading -> {
                        unreadUsersText.text = "불러오는 중..."
                        countBadge.visibility = View.GONE
                    }
                    is NotificationManagementViewModel.UnreadUsersState.Success -> {
                        if (state.users.isEmpty()) {
                            // empNum 파라미터 제거
                            val isMyDept = viewModel.isMyDepartmentTarget(notification.targetDept)

                            unreadUsersText.text = if (isMyDept) {
                                "✓ 모든 사용자가 읽었습니다"
                            } else {
                                "ℹ️ 대상 부서가 아닙니다"
                            }
                            unreadUsersText.setTextColor(requireContext().getColor(
                                if (isMyDept) android.R.color.holo_green_dark
                                else android.R.color.darker_gray
                            ))
                            countBadge.visibility = View.GONE
                        } else {
                            countBadge.text = state.users.size.toString()
                            countBadge.visibility = View.VISIBLE

                            val formattedUsers = state.users.map { user ->
                                val parts = user.split(" ")
                                if (parts.size >= 3) {
                                    val name = parts[0]
                                    val empNum = parts[1]
                                    // Repository에서 이미 잘라진 displayDept를 받음
                                    val displayDept = parts.drop(2).joinToString(" ").removeSurrounding("(", ")")

                                    android.text.SpannableStringBuilder().apply {
                                        // 사번 (굵게, 파란색)
                                        val start = length
                                        append(empNum)
                                        setSpan(
                                            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                            start,
                                            length,
                                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                        )
                                        setSpan(
                                            android.text.style.ForegroundColorSpan(
                                                requireContext().getColor(android.R.color.holo_blue_dark)
                                            ),
                                            start,
                                            length,
                                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                        )

                                        // 이름 (검정, 굵게)
                                        val nameStart = length
                                        append(" $name")
                                        setSpan(
                                            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                            nameStart,
                                            length,
                                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                        )

                                        // 소속 (작고 회색), 길이가 길면 ... 처리
                                        val deptStart = length
                                        val maxDeptLength = 12
                                        val truncatedDept = if (displayDept.length > maxDeptLength) {
                                            displayDept.take(maxDeptLength) + "..."
                                        } else {
                                            displayDept
                                        }
                                        append(" $truncatedDept")
                                        setSpan(
                                            android.text.style.ForegroundColorSpan(
                                                requireContext().getColor(android.R.color.darker_gray)
                                            ),
                                            deptStart,
                                            length,
                                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                        )
                                        setSpan(
                                            android.text.style.RelativeSizeSpan(0.88f),
                                            deptStart,
                                            length,
                                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                        )
                                    }
                                } else {
                                    android.text.SpannableStringBuilder(user)
                                }
                            }

                            unreadUsersText.text = formattedUsers.joinToString("\n") { it }
                            unreadUsersText.setTextColor(requireContext().getColor(android.R.color.black))
                        }
                    }
                    is NotificationManagementViewModel.UnreadUsersState.Error -> {
                        unreadUsersText.text = "❌ 조회 실패: ${state.message}"
                        unreadUsersText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
                        countBadge.visibility = View.GONE
                    }
                }
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("${notification.priority.displayName} 알림")
            .setView(scrollView)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun getUserEmpNum(): String? {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val empNum = prefs.getString("emp_num", null)
        return if (!empNum.isNullOrEmpty()) empNum else null
    }

    //todo:수정 시 다이얼로그 UI 변경
    private fun showEditNotificationDialog(notification: Notification) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        // 제목 수정
        val titleEdit = android.widget.EditText(requireContext()).apply {
            setText(notification.title)
            hint = "제목"
        }
        layout.addView(titleEdit)

        // 내용 수정
        val contentEdit = android.widget.EditText(requireContext()).apply {
            setText(notification.content)
            hint = "내용"
        }
        layout.addView(contentEdit)

        // 수신자 정보 초기화 (기존 알림 정보 로드)
        selectAuth.clear()
        selectDept.clear()
        selectedDeptPathGlobal.clear()

        notification.auth?.let { selectAuth.add(it.toString()) }
        notification.targetDept?.let {
            selectDept.addAll(it)
            if (!it.contains(DEPT_ALL)) {
                // targetDept에서 경로 복원
                it.lastOrNull()?.split("/")?.let { path ->
                    selectedDeptPathGlobal.addAll(path)
                }
            }
        }

        // 수신자 그룹 상태 업데이트
        fun updateReceiverStatus(textView: android.widget.TextView) {
            val authText = if (selectAuth.isNotEmpty()) {
                "권한: ${selectAuth.first()}"
            } else { notification.auth?.let { "권한: $it" } ?: "권한: 미설정" }

            val deptText = if (selectDept.isNotEmpty()) {
                "부서: ${selectDept.joinToString(", ")}"
            } else { notification.targetDept?.joinToString(", ")?.let { "부서: $it" } ?: "부서: 미설정" }

            textView.text = "수신자 그룹 - $authText, $deptText"
        }

        // 수신자 그룹 상태 표시
        val receiverStatus = android.widget.TextView(requireContext()).apply {
            setPadding(0, 20, 0, 10)
        }
        updateReceiverStatus(receiverStatus)
        layout.addView(receiverStatus)
        currentReceiverStatusCallback = { updateReceiverStatus(receiverStatus) }

        // 수신자 그룹 선택
        val receiverButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "수신자 그룹 변경"
            setOnClickListener { openReceiverDropdownDialog() }
        }
        layout.addView(receiverButton)

        // 첨부파일 관리를 위한 임시 리스트
        val editAttachments = (notification.attachmentUrl ?: emptyList()).toMutableList()
        editAttachmentUris.clear() // 수정용 첨부파일 리스트 초기화

        // 첨부파일 상태 업데이트
        fun updateAttachmentStatus(textView: android.widget.TextView) {
            val totalCount = editAttachments.size + editAttachmentUris.size
            textView.text = if (totalCount == 0) "첨부파일 없음"
            else "첨부파일 ${totalCount}개"
        }

        // 첨부파일 상태 표시
        val attachmentStatus = android.widget.TextView(requireContext()).apply {
            setPadding(0, 20, 0, 10)
        }
        updateAttachmentStatus(attachmentStatus)
        layout.addView(attachmentStatus)
        currentAttachmentStatusCallback = { updateAttachmentStatus(attachmentStatus) }

        // 첨부파일 추가
        val addFileButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "첨부파일 추가"
            setOnClickListener { openDocumentsForEdit.launch(arrayOf("image/*", "application/pdf")) }
        }
        layout.addView(addFileButton)

        // 기존 첨부파일 삭제
        if (editAttachments.isNotEmpty()) {
            val clearOldFilesButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
                text = "기존 첨부파일 모두 삭제"
                setOnClickListener {
                    editAttachments.clear()
                    updateAttachmentStatus(attachmentStatus)
                }
            }
            layout.addView(clearOldFilesButton)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("알림 수정")
            .setView(layout)
            .setPositiveButton("수정") { _, _ ->
                val newTitle = titleEdit.text.toString().trim()
                val newContent = contentEdit.text.toString().trim()

                if (newTitle.isNotEmpty() && newContent.isNotEmpty()) {
                    // 기존 첨부파일 + 새로 추가된 첨부파일 병합
                    val finalAttachments = editAttachments + editAttachmentUris.map { it.toString() }

                    val targetDeptList = if (selectDept.contains(DEPT_ALL)) {
                        listOf(DEPT_ALL)
                    } else if (selectedDeptPathGlobal.isNotEmpty()) {
                        buildDeptTargetsFromPath(selectedDeptPathGlobal)
                    } else {
                        notification.targetDept
                    }

                    viewModel.updateNotification(
                        notificationId = notification.id,
                        title = newTitle,
                        content = newContent,
                        priority = notification.priority,
                        auth = selectAuth.firstOrNull()?.toIntOrNull() ?: notification.auth,
                        targetDept = targetDeptList,
                        attachmentUrl = editAttachments,
                        newAttachmentUris = editAttachmentUris.toList()
                    )
                    currentAttachmentStatusCallback = null
                    currentReceiverStatusCallback = null
                    editAttachmentUris.clear()
                } else {
                    Toast.makeText(requireContext(), "제목과 내용을 모두 입력하세요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소") { _, _ ->
                currentAttachmentStatusCallback = null
                currentReceiverStatusCallback = null
                editAttachmentUris.clear()
            }
            .show()
    }

    private fun showDeleteConfirmDialog(notification: Notification) {
        AlertDialog.Builder(requireContext())
            .setTitle("알림 삭제")
            .setMessage("'${notification.title}' 알림을 삭제하시겠습니까?\n삭제된 알림은 복구할 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                viewModel.deleteNotification(notification.id)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}