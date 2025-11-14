// app/src/main/java/com/jjangdol/biorhythm/ui/main/NotificationDetailFragment.kt
package com.jjangdol.biorhythm.ui.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentNotificationDetailBinding
import com.jjangdol.biorhythm.data.model.Notification
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

@AndroidEntryPoint
class NotificationDetailFragment : Fragment(R.layout.fragment_notification_detail) {

    private var _binding: FragmentNotificationDetailBinding? = null
    private val binding get() = _binding!!

    private var notification: Notification? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentNotificationDetailBinding.bind(view)

        // Arguments에서 Notification 받기
        notification = arguments?.getParcelable("notification")

        setupUI()
        setupClickListeners()
    }

    private fun setupUI() {
        notification?.let { notif ->
            // 제목 및 우선순위
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

            // 첨부파일
            setupAttachments(notif.attachmentUrl ?: emptyList())
        }
    }

    private fun setupAttachments(urls: List<String>) {
        if (urls.isEmpty()) {
            binding.chipGroupAttachments.visibility = View.GONE
            binding.attachmentPreviewContainer.visibility = View.GONE
            return
        }

        binding.tvAttachmentsLabel.visibility = View.VISIBLE
        binding.chipGroupAttachments.visibility = View.VISIBLE
        binding.chipGroupAttachments.removeAllViews()

        // 첨부파일 칩 추가
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
            binding.chipGroupAttachments.addView(chip)
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