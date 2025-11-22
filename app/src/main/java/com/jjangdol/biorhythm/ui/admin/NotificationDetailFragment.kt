// app/src/main/java/com/jjangdol/biorhythm/ui/main/NotificationDetailFragment.kt
package com.jjangdol.biorhythm.ui.main

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentNotificationDetailBinding
import com.jjangdol.biorhythm.data.model.Notification
import com.jjangdol.biorhythm.vm.NotificationManagementViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

@AndroidEntryPoint
class NotificationDetailFragment : Fragment(R.layout.fragment_notification_detail) {

    private var _binding: FragmentNotificationDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NotificationManagementViewModel by viewModels()

    private var notification: Notification? = null
    private var isAdminMode: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentNotificationDetailBinding.bind(view)

        // Arguments에서 Notification과 모드 받기
        notification = arguments?.getParcelable("notification")
        isAdminMode = arguments?.getBoolean("isAdminMode", false) ?: false

        setupUI()
        setupClickListeners()

        // 관리자 모드일 때만 읽지 않은 사용자 로드
        if (isAdminMode) {
            loadUnreadUsers()
        }
    }

    private fun setupUI() {
        notification?.let { notif ->
            // 제목
            binding.tvTitle.text = notif.title

            // 우선순위별 색상
            val priorityColor = when (notif.priority) {
                com.jjangdol.biorhythm.data.model.NotificationPriority.HIGH ->
                    requireContext().getColor(R.color.priority_high)
                com.jjangdol.biorhythm.data.model.NotificationPriority.NORMAL ->
                    requireContext().getColor(R.color.priority_normal)
                com.jjangdol.biorhythm.data.model.NotificationPriority.LOW ->
                    requireContext().getColor(R.color.priority_low)
            }
            binding.toolbar.setBackgroundColor(priorityColor)

            // 내용
            binding.tvContent.text = notif.content

            // 작성일
            val dateFormat = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
            val createdAtText = notif.createdAt?.toDate()?.let { date ->
                "작성일: ${dateFormat.format(date)}"
            } ?: "작성일: 알 수 없음"
            binding.tvCreatedAt.text = createdAtText

            // 관리자 모드일 때만 정보 표시
            if (isAdminMode) {
                binding.tvMetaInfo.visibility = View.VISIBLE
                binding.tvMetaInfo.text = buildString {
                    append("${if (notif.active) "✓ 활성" else "✗ 비활성"}  ")
                    append("•  ${notif.priority.displayName}")

                    notif.auth?.let {
                        val authText = when(it) {
                            2 -> "전체"
                            else -> it.toString()
                        }
                        append("\n🔐 권한: $authText")
                    }

                    notif.targetDept?.let {
                        val deptText = if (it.size > 2) {
                            "${it.take(2).joinToString(", ")} 외 ${it.size - 2}개"
                        } else {
                            it.joinToString(", ")
                        }
                        append("  •  🏢 $deptText")
                    }

                    notif.attachmentUrl?.let {
                        if (it.isNotEmpty()) append("  •  📎 ${it.size}개")
                    }
                }
            } else {
                binding.tvMetaInfo.visibility = View.GONE
            }

            // 첨부파일
            setupAttachments(notif.attachmentUrl ?: emptyList())

            // 읽지 않은 사용자 카드는 관리자 모드일 때만 표시
            binding.unreadUsersCard.visibility = if (isAdminMode) View.VISIBLE else View.GONE
        }
    }

    private fun loadUnreadUsers() {
        val notif = notification ?: return
        val currentUserEmpNum = getUserEmpNum()

        if (currentUserEmpNum == null) {
            binding.tvUnreadUsers.text = "⚠️ 사용자 정보를 찾을 수 없습니다"
            return
        }

        viewModel.loadUnreadUsers(
            notif.id,
            notif.auth,
            notif.targetDept,
            currentUserEmpNum
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.unreadUsersState.collectLatest { state ->
                when (state) {
                    is NotificationManagementViewModel.UnreadUsersState.Initial -> {
                        binding.tvUnreadUsers.text = "불러오는 중..."
                        binding.tvUnreadCount.visibility = View.GONE
                    }
                    is NotificationManagementViewModel.UnreadUsersState.Loading -> {
                        binding.tvUnreadUsers.text = "불러오는 중..."
                        binding.tvUnreadCount.visibility = View.GONE
                    }
                    is NotificationManagementViewModel.UnreadUsersState.Success -> {
                        if (state.users.isEmpty()) {
                            val isMyDept = viewModel.isMyDepartmentTarget(notif.targetDept)

                            binding.tvUnreadUsers.text = if (isMyDept) {
                                "✓ 모든 사용자가 읽었습니다"
                            } else {
                                "ℹ️ 대상 부서가 아닙니다"
                            }
                            binding.tvUnreadUsers.setTextColor(requireContext().getColor(
                                if (isMyDept) android.R.color.holo_green_dark
                                else android.R.color.darker_gray
                            ))
                            binding.tvUnreadCount.visibility = View.GONE
                        } else {
                            binding.tvUnreadCount.text = state.users.size.toString()
                            binding.tvUnreadCount.visibility = View.VISIBLE

                            val formattedUsers = state.users.map { user ->
                                val parts = user.split(" ")
                                if (parts.size >= 3) {
                                    val name = parts[0]
                                    val empNum = parts[1]
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

                                        // 소속 (작고 회색)
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

                            binding.tvUnreadUsers.text = formattedUsers.joinToString("\n") { it }
                            binding.tvUnreadUsers.setTextColor(requireContext().getColor(android.R.color.black))
                        }
                    }
                    is NotificationManagementViewModel.UnreadUsersState.Error -> {
                        binding.tvUnreadUsers.text = "❌ 조회 실패: ${state.message}"
                        binding.tvUnreadUsers.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
                        binding.tvUnreadCount.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun setupAttachments(urls: List<String>) {
        if (urls.isEmpty()) {
            binding.attachmentPreviewContainer.visibility = View.GONE
            return
        }

        // 미리보기
        setupPreview(urls)
    }

    private fun setupPreview(urls: List<String>) {
        binding.attachmentPreviewContainer.visibility = View.VISIBLE
        binding.previewStrip.removeAllViews()

        urls.forEach { url ->
            val slot = makeThumbSlot()
            val image = slot.getChildAt(0) as android.widget.ImageView

            slot.setOnClickListener { openAttachmentUrl(url) }

            when {
                isImageUrl(url) -> {
                    try {
                        com.bumptech.glide.Glide.with(this)
                            .load(url)
                            .thumbnail(0.25f)
                            .into(image)
                    } catch (_: Throwable) {
                    }
                }
                isPdfUrl(url) -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val file = downloadToCacheHttps(url, guessFileName(url))
                        val bmp = file?.let { renderPdf(it, 320, 320) }
                        if (bmp != null) image.setImageBitmap(bmp)
                    }
                }
            }
            binding.previewStrip.addView(slot)
        }
    }

    private fun makeThumbSlot(): android.widget.FrameLayout {
        return android.widget.FrameLayout(requireContext()).apply {
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
        }
    }

    private fun setupClickListeners() {
        // 뒤로 가기
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun openAttachmentUrl(url: String) {
        val uri = Uri.parse(url)
        val mime = guessMimeFromUrl(url)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (mime != null) {
                setDataAndType(uri, mime)
            } else {
                data = uri
            }
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getUserEmpNum(): String? {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val empNum = prefs.getString("emp_num", null)
        return if (!empNum.isNullOrEmpty()) empNum else null
    }

    // Utility functions
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun guessMimeFromUrl(url: String): String? {
        val ext = MimeTypeMap.getFileExtensionFromUrl(url)
        return if (ext.isNullOrBlank()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
    }

    private fun guessFileName(url: String): String {
        val cleaned = url.substringBefore('?')
        val name = cleaned.substringAfterLast('/')
        return if (name.isBlank()) "attachment" else name
    }

    private fun isImageUrl(url: String): Boolean {
        val mime = guessMimeFromUrl(url) ?: return false
        return mime.startsWith("image/")
    }

    private fun isPdfUrl(url: String): Boolean {
        val mime = guessMimeFromUrl(url)
        return (mime == "application/pdf") || url.endsWith(".pdf", ignoreCase = true)
    }

    private suspend fun downloadToCacheHttps(url: String, fileName: String): java.io.File? =
        withContext(Dispatchers.IO) {
            try {
                val cacheDir = java.io.File(requireContext().cacheDir, "attachments").apply { mkdirs() }
                val local = java.io.File(cacheDir, fileName)
                if (local.exists()) return@withContext local

                val conn = java.net.URL(url).openConnection()
                conn.getInputStream().use { input ->
                    java.io.FileOutputStream(local).use { out -> input.copyTo(out) }
                }
                local
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun renderPdf(pdfFile: java.io.File, reqW: Int, reqH: Int): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val pfd = android.os.ParcelFileDescriptor.open(
                    pdfFile,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
                android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val scale = minOf(reqW.toFloat() / page.width, reqH.toFloat() / page.height)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = android.graphics.Bitmap.createBitmap(
                            w,
                            h,
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        page.render(
                            bmp,
                            null,
                            null,
                            android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        bmp
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}