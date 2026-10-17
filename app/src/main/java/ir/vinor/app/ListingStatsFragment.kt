package ir.vinor.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentListingStatsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** آمار یک آگهی، جدا از فهرست و مدیریت. */
class ListingStatsFragment : Fragment() {

    private var _binding: FragmentListingStatsBinding? = null
    private val binding get() = _binding!!
    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)
    private var snapshot = JSONObject()
    private var loadGen = 0
    private var finished = false
    private var failed = false
    private var skipNextResume = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        skipNextResume = true
        _binding = FragmentListingStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        snapshot = ListingStats.read(arguments?.getString(ARG_SNAPSHOT))
        binding.statsHeader.pageTitle.text = ListingStats.TITLE
        binding.statsHeader.pageBack.setOnClickListener {
            if (!findNavController().navigateUp()) {
                (activity as? MainActivity)?.openMyProperty()
            }
        }
        binding.statsRefresh.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.vinor_primary_red))
        binding.statsRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(requireContext(), R.color.vinor_raised),
        )
        binding.statsRefresh.setOnRefreshListener { load() }
        bind()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResume) {
            skipNextResume = false
            return
        }
        if (_binding != null) load()
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun bind() {
        val host = _binding ?: return
        val land = ListingStats.forScreen(snapshot)
        val ready = ListingWeek.present(land).available
        when (ListingStats.phase(ready, finished, failed)) {
            ListingStats.Phase.CONTENT -> {
                host.statsContent.isVisible = true
                host.statsMessage.isVisible = false
                ListingWeekPanel.show(host.statsHost, land)
            }
            ListingStats.Phase.EMPTY -> {
                host.statsContent.isVisible = false
                host.statsHost.removeAllViews()
                host.statsMessage.isVisible = true
                host.statsMessage.text = ListingStats.EMPTY
            }
            ListingStats.Phase.OFFLINE -> {
                host.statsContent.isVisible = false
                host.statsHost.removeAllViews()
                host.statsMessage.isVisible = true
                host.statsMessage.text = VinorPageLoad.OFFLINE
            }
            ListingStats.Phase.LOADING -> {
                host.statsContent.isVisible = false
                host.statsMessage.isVisible = false
            }
        }
        host.statsRefresh.isRefreshing = !finished
    }

    private fun load() {
        val ctx = context ?: return
        if (!PartnerApi.hasSession()) {
            binding.statsRefresh.isRefreshing = false
            (activity as? MainActivity)?.openLogin()
            return
        }
        finished = false
        failed = false
        bind()
        val gen = ++loadGen
        scope.launch {
            val wasFresh = withContext(Dispatchers.IO) { PartnerApi.isFresh(ListingStats.PATH, ctx) }
            try {
                val fresh = PartnerApi.loadDisplay(ListingStats.PATH, ctx) { data ->
                    if (_binding == null || gen != loadGen) return@loadDisplay
                    if (!VinorPageLoad.canRemember(data)) return@loadDisplay
                    snapshot = ListingStats.applyPortfolio(snapshot, data)
                    persist()
                    finished = true
                    failed = false
                    bind()
                }
                if (_binding == null || gen != loadGen) return@launch
                if (VinorPageLoad.needsLogin(fresh)) {
                    ListingMemory.clearSession()
                    (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    return@launch
                }
                if (VinorPageLoad.canRemember(fresh)) {
                    snapshot = ListingStats.applyPortfolio(snapshot, fresh)
                    persist()
                    failed = false
                } else {
                    failed = VinorPageLoad.networkFailed(wasFresh, fresh)
                    if (failed && ListingWeek.present(ListingStats.forScreen(snapshot)).available) {
                        VinorPageLoad.showOffline(binding.root) { load() }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_binding == null || gen != loadGen) return@launch
                failed = true
                if (ListingWeek.present(ListingStats.forScreen(snapshot)).available) {
                    VinorPageLoad.showOffline(binding.root) { load() }
                }
            } finally {
                if (_binding != null && gen == loadGen) {
                    finished = true
                    bind()
                }
            }
        }
    }

    private fun persist() {
        arguments?.putString(ARG_SNAPSHOT, ListingStats.pack(snapshot))
    }

    companion object {
        const val ARG_SNAPSHOT = ListingStats.ARG_SNAPSHOT
    }
}
