package ir.vinor.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import ir.vinor.app.databinding.FragmentAddHubBinding
import ir.vinor.app.databinding.ItemAddHubRowBinding

/** صفحه میانی فوتر افزودن — انتخاب کار قبل از فرم. */
class AddHubFragment : Fragment() {

    private var _binding: FragmentAddHubBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAddHubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.addHubHeader.pageTitle.text = AddHubActions.PAGE_TITLE
        binding.addHubHeader.pageBack.setOnClickListener {
            (activity as? MainActivity)?.onBackPressedDispatcher?.onBackPressed()
                ?: parentFragmentManager.popBackStack()
        }
        bindRows()
    }

    private fun bindRows() {
        val list = binding.addHubList
        list.removeAllViews()
        val rows = AddHubActions.rows()
        rows.forEachIndexed { index, row ->
            val item = ItemAddHubRowBinding.inflate(layoutInflater, list, true)
            item.addHubRowIcon.setImageResource(row.iconRes)
            item.addHubRowTitle.text = row.title
            item.addHubRowSubtitle.text = row.subtitle
            item.addHubRowDivider.visibility = if (index == rows.lastIndex) View.GONE else View.VISIBLE
            val path = row.path
            if (path.isNullOrBlank()) {
                item.root.isClickable = false
                item.root.isFocusable = false
                item.root.background = null
                item.addHubRowChevron.alpha = 0.35f
            } else {
                item.root.contentDescription = "${row.title}. ${row.subtitle}"
                item.root.setOnClickListener {
                    (activity as? MainActivity)?.openPath(path)
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
