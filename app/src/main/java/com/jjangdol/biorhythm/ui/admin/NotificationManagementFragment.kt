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

@AndroidEntryPoint
class NotificationManagementFragment : Fragment(R.layout.fragment_notification_management) {

    private var _binding: FragmentNotificationManagementBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NotificationManagementViewModel by viewModels()
    private lateinit var notificationAdapter: NotificationAdapter

    private var selectedPriority = NotificationPriority.NORMAL
    private var filterPriority: NotificationPriority? = null

    private val selectedAttachmentUris = mutableListOf<Uri>()

    private val openDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments())
    { uris ->
        if (!uris.isNullOrEmpty()) {
            addAttachments(uris)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentNotificationManagementBinding.bind(view)

        setupUI()
        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
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
                viewModel.createNotification(title, content, selectedPriority, attachments = selectedAttachmentUris.toList())
            }
        }
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

    private fun openAttachmentUrl(url: String) {
        try
        {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

            startActivity(Intent.createChooser(intent, "첨부파일 열기"))
        }
        catch (e: Exception)
        {
            Toast.makeText(requireContext(), "파일을 열 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearForm() {
        binding.etNotificationTitle.text?.clear()
        binding.etNotificationContent.text?.clear()
        binding.chipGroupPriority.check(R.id.chipNormal)
        selectedPriority = NotificationPriority.NORMAL
        selectedAttachmentUris.clear()
    }

    private fun showNotificationDetailDialog(notification: Notification) {
        val priorityColor = when (notification.priority) {
            NotificationPriority.HIGH -> "#F44336"
            NotificationPriority.NORMAL -> "#2196F3"
            NotificationPriority.LOW -> "#4CAF50"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("${notification.priority.displayName} 알림")
            .setMessage(
                "제목: ${notification.title}\n\n" +
                        "내용: ${notification.content}\n\n" +
                        "작성일: ${notification.createdAt?.toDate()?.toString() ?: "알 수 없음"}\n" +
                        "상태: ${if (notification.active) "활성" else "비활성"}"
            )
            .setPositiveButton("확인", null)
            .show()
    }

    private fun showEditNotificationDialog(notification: Notification) {
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val titleEdit = android.widget.EditText(requireContext()).apply {
            setText(notification.title)
            hint = "제목"
        }

        val contentEdit = android.widget.EditText(requireContext()).apply {
            setText(notification.content)
            hint = "내용"
            maxLines = 3
        }

        layout.addView(titleEdit)
        layout.addView(contentEdit)

        AlertDialog.Builder(requireContext())
            .setTitle("알림 수정")
            .setView(layout)
            .setPositiveButton("수정") { _, _ ->
                val newTitle = titleEdit.text.toString().trim()
                val newContent = contentEdit.text.toString().trim()

                if (newTitle.isNotEmpty() && newContent.isNotEmpty()) {
                    viewModel.updateNotification(
                        notification.id,
                        newTitle,
                        newContent,
                        notification.priority
                    )
                } else {
                    Toast.makeText(requireContext(), "제목과 내용을 모두 입력하세요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
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