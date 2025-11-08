package com.jjangdol.biorhythm.ui.admin

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentAdminChecklistManagementBinding
import com.jjangdol.biorhythm.vm.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import com.google.android.material.textfield.TextInputEditText


@AndroidEntryPoint
class AdminChecklistManagementFragment : Fragment(R.layout.fragment_admin_checklist_management) {

    private var _binding: FragmentAdminChecklistManagementBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()
    private lateinit var adapter: AdminChecklistAdapter
    private var newQuestionWeight = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAdminChecklistManagementBinding.bind(view)

        setupToolbar()
        setupUI()
        setupRecyclerView()
        setupClickListeners()
        observeData()

        addOptionView(1)
        addOptionView(2)
    }


    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupUI() {
        // 새 문항 가중치 입력 감지 (0~100 제한)
        binding.etNewWeight.addTextChangedListener {
            val textValue = it.toString()
            val value = textValue.toIntOrNull() ?: 0

            // 0~100 범위 유지
            if (value > 100) {
                binding.etNewWeight.setText("100")
                binding.etNewWeight.setSelection(binding.etNewWeight.text?.length ?: 0)
                Toast.makeText(requireContext(), "문항 가중치는 100을 초과할 수 없습니다.", Toast.LENGTH_SHORT).show()
                newQuestionWeight = 100
            } else {
                newQuestionWeight = value
            }
        }
    }


    private fun setupRecyclerView() {
        adapter = AdminChecklistAdapter(
            onWeightChanged = { position, weight ->
                viewModel.updateWeight(position, weight)
            },
            onDelete = { position ->
                showDeleteConfirmDialog(position)
            },
            onEdit = { position, newQuestion ->
                viewModel.updateQuestion(position, newQuestion)
                Toast.makeText(requireContext(), "문항이 수정되었습니다", Toast.LENGTH_SHORT).show()
            }
        )

        binding.rvWeights.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AdminChecklistManagementFragment.adapter
        }
    }

    private fun setupClickListeners() {
        // 선택지 추가
        binding.btnAddOption.setOnClickListener {
            val count = binding.layoutOptionsContainer.childCount
            if (count < 5) addOptionView(count + 1)
            else Toast.makeText(requireContext(), "최대 5개까지 가능합니다.", Toast.LENGTH_SHORT).show()
        }

        // 선택지 삭제
        binding.btnRemoveOption.setOnClickListener {
            val count = binding.layoutOptionsContainer.childCount
            if (count > 2) binding.layoutOptionsContainer.removeViewAt(count - 1)
            else Toast.makeText(requireContext(), "최소 2개 이상 필요합니다.", Toast.LENGTH_SHORT).show()
        }

        // 새 문항 추가
        binding.btnAddQuestion.setOnClickListener {
            val question = binding.etNewQuestion.text.toString().trim()
            if (question.isEmpty()) {
                Toast.makeText(requireContext(), "문항 내용을 입력해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 문항 가중치 값 가져오기
            val newWeight = binding.etNewWeight.text.toString().toIntOrNull() ?: 0
            if (newWeight < 0 || newWeight > 100) {
                Toast.makeText(requireContext(), "문항 가중치는 0~100 사이여야 합니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 전체 문항 가중치 합 검사
            val currentTotal = viewModel.items.value.sumOf { it.weight }
            val newTotal = currentTotal + newWeight
            if (newTotal > 100) {
                Toast.makeText(
                    requireContext(),
                    "전체 문항의 가중치 합은 100을 초과할 수 없습니다. (현재 합: $currentTotal)",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // 선택지 수집
            val (options, optionWeights) = collectOptionsFromUI()
            if (options.size < 2) {
                Toast.makeText(requireContext(), "선택지는 최소 2개 필요합니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 문항 추가
            viewModel.addQuestion(
                question = question,
                weight = newWeight,
                options = options,
                optionWeights = optionWeights
            )

            // 입력 초기화
            binding.etNewQuestion.text?.clear()
            binding.etNewWeight.setText("0")
            binding.layoutOptionsContainer.removeAllViews()
            addOptionView(1)
            addOptionView(2)

            Toast.makeText(requireContext(), "문항이 추가되었습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addOptionView(index: Int, initWeight: Int = 0) {
        val inflater = layoutInflater
        val optionView = inflater.inflate(R.layout.item_option_edit, binding.layoutOptionsContainer, false)

        val tvLabel = optionView.findViewById<TextView>(R.id.tvOptionLabel)
        val etText = optionView.findViewById<TextInputEditText>(R.id.etOptionText)
        val etWeight = optionView.findViewById<TextInputEditText>(R.id.etOptionWeight)

        tvLabel.text = "선택지 $index"
        etWeight.setText(initWeight.toString())

        // 숫자 이외 입력 방지 및 최대 100 제한
        etWeight.addTextChangedListener {
            val textValue = it.toString()
            val value = textValue.toIntOrNull() ?: 0

            if (value > 100) {
                etWeight.setText("100")
                etWeight.setSelection(etWeight.text?.length ?: 0)
                Toast.makeText(binding.root.context, "가중치는 100을 초과할 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.layoutOptionsContainer.addView(optionView)
    }



    /** 선택지 입력값 수집 */
    private fun collectOptionsFromUI(): Pair<List<String>, List<Int>> {
        val options = mutableListOf<String>()
        val weights = mutableListOf<Int>()

        for (i in 0 until binding.layoutOptionsContainer.childCount) {
            val v = binding.layoutOptionsContainer.getChildAt(i)
            val etText = v.findViewById<TextInputEditText>(R.id.etOptionText)
            val etWeight = v.findViewById<TextInputEditText>(R.id.etOptionWeight)

            val text = etText.text.toString().trim()
            val weight = etWeight.text.toString().toIntOrNull() ?: 0

            if (text.isNotEmpty()) {
                options.add(text)
                weights.add(weight.coerceIn(0, 100)) // 0~100 범위 제한
            }
        }

        return options to weights
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.items.collectLatest { items ->
                adapter.submitList(items)
                binding.tvQuestionCount.text = "총 ${items.size}개"

                // 빈 상태 처리
                if (items.isEmpty()) {
                    binding.rvWeights.visibility = View.GONE
                    binding.emptyLayout.visibility = View.VISIBLE
                } else {
                    binding.rvWeights.visibility = View.VISIBLE
                    binding.emptyLayout.visibility = View.GONE
                }
            }
        }
    }

    private fun showDeleteConfirmDialog(position: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("문항 삭제")
            .setMessage("이 문항을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                viewModel.removeQuestion(position)
                Toast.makeText(requireContext(), "문항이 삭제되었습니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}