// app/src/main/java/com/jjangdol/biorhythm/ui/main/NotificationFragment.kt
package com.jjangdol.biorhythm.ui.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentNotificationBinding
import com.jjangdol.biorhythm.data.model.Notification
import com.jjangdol.biorhythm.data.model.NotificationPriority
import com.jjangdol.biorhythm.vm.UserNotificationViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@AndroidEntryPoint
class NotificationFragment : Fragment(R.layout.fragment_notification) {

    private var _binding: FragmentNotificationBinding? = null
    private val binding get() = _binding!!

    private val viewModel: UserNotificationViewModel by viewModels()
    private lateinit var notificationAdapter: UserNotificationAdapter

    private var filterPriority: NotificationPriority? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentNotificationBinding.bind(view)

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        notificationAdapter = UserNotificationAdapter(
            onItemClick = { notification ->
                viewModel.markAsRead(notification.id)
                showNotificationDetail(notification)
            },
            onMoreClick = { notification, isExpanded ->
                // 확장/축소 처리는 어댑터에서 자동 처리
            },
            onMarkReadClick = { notification ->
                viewModel.markAsRead(notification.id)
                // 즉시 어댑터 갱신
                notificationAdapter.notifyDataSetChanged()
            },
            onShareClick = { notification ->
                shareNotification(notification)
            },
            isNotificationRead = { notificationId ->
                viewModel.isNotificationRead(notificationId)
            }
        )

        binding.recyclerViewNotifications.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = notificationAdapter
        }
    }

    private fun setupClickListeners() {
        // 필터 칩 그룹
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            filterPriority = when (checkedIds.firstOrNull()) {
                R.id.chipHigh -> NotificationPriority.HIGH
                R.id.chipNormal -> NotificationPriority.NORMAL
                R.id.chipLow -> NotificationPriority.LOW
                else -> null
            }
            viewModel.setFilter(filterPriority)
        }

        // 새로고침 버튼
        binding.btnRefresh.setOnClickListener {
            viewModel.refreshNotifications()
            Toast.makeText(requireContext(), "알림을 새로고침했습니다", Toast.LENGTH_SHORT).show()
        }

        // 모두 읽음 처리 버튼
        binding.btnMarkAllRead.setOnClickListener {
            showMarkAllReadDialog()
        }

        // 선택 읽음 처리 버튼
        binding.btnMarkSelectedRead.setOnClickListener {
            val selectedIds = notificationAdapter.getSelectedUnreadIds()  // 읽지 않은 것만
            if (selectedIds.isNotEmpty()) {
                viewModel.markMultipleAsRead(selectedIds)
                notificationAdapter.clearSelection()
                binding.bottomActionLayout.visibility = View.GONE
            } else {
                Toast.makeText(requireContext(), "읽음 처리할 알림을 선택하세요", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openAttachmentUrl(url: String) {
        val uri = Uri.parse(url)
        val mime = guessMimeFromUrl(url)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (mime != null) { setDataAndType(uri, mime) }
            else { data = uri }
        }
        try
        { startActivity(intent) }
        catch (e: ActivityNotFoundException)
        { Toast.makeText(context, "열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show() }
    }

    private fun guessMimeFromUrl(url: String): String? {
        val ext = MimeTypeMap.getFileExtensionFromUrl(url)
        return if (ext.isNullOrBlank()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
    }

    private fun downloadAttachment(url: String, fileName: String = guessFileName(url)) {
        try
        {
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                .setTitle(fileName)
                .setDescription("첨부파일 다운로드 중")
                .setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,fileName
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val dm = requireContext()
                .getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            dm.enqueue(request)

            Toast.makeText(requireContext(), "다운로드를 시작했습니다.", Toast.LENGTH_SHORT).show()
        }
        catch (e: Exception)
        {
            Toast.makeText(requireContext(), "다운로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun guessFileName(url: String): String {
        val cleaned = url.substringBefore('?')
        val name = cleaned.substringAfterLast('/')
        return if (name.isBlank()) "attachment" else name
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.notifications.collectLatest { notifications ->
                notificationAdapter.submitList(notifications)

                // 빈 상태 처리
                binding.emptyLayout.visibility =
                    if (notifications.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.unreadCount.collectLatest { count ->
                binding.tvNotificationSummary.text = "읽지 않은 알림이 ${count}개 있습니다"

                // 모두 읽음 버튼 활성화/비활성화
                binding.btnMarkAllRead.isEnabled = count > 0
            }
        }

        //  읽음 상태 변경 관찰 추가
        viewLifecycleOwner.lifecycleScope.launch {
            // UserNotificationRepository의 readNotificationIds Flow 관찰
            // (실제로는 UserNotificationViewModel을 통해 접근해야 함)
            // 임시로 UI 상태 변경을 통해 갱신
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is UserNotificationViewModel.UiState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    is UserNotificationViewModel.UiState.Success -> {
                        binding.progressBar.visibility = View.GONE
                        if (state.message.isNotEmpty()) {
                            Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                            // 성공 메시지가 있을 때 어댑터 갱신
                            notificationAdapter.notifyDataSetChanged()
                        }
                    }
                    is UserNotificationViewModel.UiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.attachmentEvents.collectLatest { event ->
                when (event) {
                    is UserNotificationViewModel.AttachmentEvent.Open -> {
                        openAttachmentUrl(event.url)
                    }
                    is UserNotificationViewModel.AttachmentEvent.Download -> {
                        downloadAttachment(event.url, event.fileName)
                    }
                    is UserNotificationViewModel.AttachmentEvent.Message -> {
                        Toast.makeText(requireContext(), event.text, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // todo 미리보기 구현

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun isImageUrl(url: String): Boolean {
        val mime = guessMimeFromUrl(url) ?: return false
        return mime.startsWith("image/")
    }

    private fun isPdfUrl(url: String): Boolean {
        val mime = guessMimeFromUrl(url)
        return (mime == "application/pdf") || url.endsWith(".pdf", ignoreCase = true)
    }

    private suspend fun downloadToCacheHttps(url: String, fileName: String): java.io.File? = withContext(Dispatchers.IO) {
            try {
                val cacheDir = java.io.File(requireContext().cacheDir, "attachments").apply { mkdirs() }
                val local = java.io.File(cacheDir, fileName)
                if (local.exists()) return@withContext local

                val conn = java.net.URL(url).openConnection()
                conn.getInputStream().use { input ->
                    java.io.FileOutputStream(local).use { out -> input.copyTo(out) }
                }
                local
            } catch (_: Exception) { null }
        }

    // PDF 1페이지 썸네일 (캐시 파일 필요)
    private suspend fun renderPdf(pdfFile: java.io.File, reqW: Int, reqH: Int): android.graphics.Bitmap? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val pfd = android.os.ParcelFileDescriptor.open(pdfFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val scale = minOf(reqW.toFloat() / page.width, reqH.toFloat() / page.height)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            } catch (_: Exception) { null }
        }


    private fun showNotificationDetail(notification: Notification) {
        val inflater = layoutInflater
        val view = inflater.inflate(R.layout.dialog_notification_detail, null)
        val tvContent = view.findViewById<TextView>(R.id.tvContent)
        val chipGroup =
            view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupAttachments)
        val container = view as android.view.ViewGroup

        // 본문
        tvContent.text = notification.content

        // 첨부 URL 리스트
        val urls: List<String> = notification.attachmentUrl ?: emptyList()
        if (urls.isNotEmpty()) {
            chipGroup.visibility = View.VISIBLE
            chipGroup.removeAllViews()

            urls.forEachIndexed { idx, url ->
                val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                    text = buildString {
                        append("첨부 ")
                        append(idx + 1)
                        val name = guessFileName(url)
                        if (name.isNotBlank() && name != "attachment") append("  ($name)")
                    }
                    isClickable = true
                    isCheckable = false

                    setOnClickListener { openAttachmentUrl(url) }
                }
                chipGroup.addView(chip)
            }
        } else {
            chipGroup.visibility = View.GONE
        }

        //미리보기
        val previewTitle = TextView(requireContext()).apply {
            text = "미리보기"
            setPadding(16.dp(), 12.dp(), 16.dp(), 4.dp())
            textSize = 14f
            setTextColor(requireContext().getColor(R.color.black))
        }
        val scroll = android.widget.HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(8.dp(), 0, 8.dp(), 8.dp())
        }
        val strip = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        scroll.addView(strip)
        container.addView(previewTitle)
        container.addView(scroll)

        fun makeThumbSlot(): android.widget.FrameLayout =
            android.widget.FrameLayout(requireContext()).apply {
                val size = 96.dp()
                val lp = android.view.ViewGroup.MarginLayoutParams(size, size)
                    .apply { rightMargin = 10.dp() }
                layoutParams = lp
                clipToOutline = true
                // 썸네일 이미지
                addView(android.widget.ImageView(requireContext()).apply {
                    id = View.generateViewId()
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                })
                // 타입 배지
                addView(android.widget.ImageView(requireContext()).apply {
                    tag = "badge"
                    layoutParams = android.widget.FrameLayout.LayoutParams(24.dp(), 24.dp()).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.END
                        marginEnd = 6.dp(); topMargin = 6.dp()
                    }
                })
            }

        urls.forEach { url ->
            val slot = makeThumbSlot()
            val image = slot.getChildAt(0) as android.widget.ImageView
            val badge = slot.findViewWithTag<android.widget.ImageView>("badge")

            slot.setOnClickListener { openAttachmentUrl(url) }

            when {
                isImageUrl(url) -> {
                    // Glide 권장(있으면 사용). 없으면 placeholder만 표시.
                    try {
                        com.bumptech.glide.Glide.with(this)
                            .load(url)
                            .thumbnail(0.25f)
                            .into(image)
                    } catch (_: Throwable) {
                    }
                    badge.setImageDrawable(null)
                }
                isPdfUrl(url) -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val file = downloadToCacheHttps(url, guessFileName(url))
                        val bmp = file?.let { renderPdf(it, 320, 320) }
                        if (bmp != null) image.setImageBitmap(bmp)
                    }
                }
                else -> {
                    chipGroup.visibility = View.GONE
                }
            }
            strip.addView(slot)
        }

        // 알림 다이얼로그
        AlertDialog.Builder(requireContext())
            .setTitle("${notification.priority.displayName} 알림")
            .setView(view)
            .setPositiveButton("확인", null)
            .setNeutralButton("공유") { _, _ ->
                shareNotification(notification)
            }
            .show()
    }

    private fun shareNotification(notification: Notification) {
        val shareText = "${notification.title}\n\n${notification.content}\n\n- ${notification.priority.displayName} 알림"

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_SUBJECT, notification.title)
        }

        startActivity(Intent.createChooser(shareIntent, "알림 공유"))
    }

    private fun showMarkAllReadDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("모든 알림 읽음 처리")
            .setMessage("모든 알림을 읽음으로 처리하시겠습니까?")
            .setPositiveButton("읽음 처리") { _, _ ->
                viewModel.markAllAsRead()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}