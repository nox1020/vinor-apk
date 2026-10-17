package ir.vinor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import ir.vinor.app.databinding.FragmentMyPropertyManageBinding
import ir.vinor.app.databinding.ItemProfileLinkBinding
import ir.vinor.app.databinding.SheetPropertyDeleteBinding
import ir.vinor.app.databinding.SheetPropertyPriceBinding
import ir.vinor.app.databinding.SheetPropertyPublishBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** صفحهٔ مدیریت ملک، قبل از جزئیات. */
class MyPropertyManageFragment : Fragment() {

    private var _binding: FragmentMyPropertyManageBinding? = null
    private val binding get() = _binding!!
    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)
    private var snapshot = JSONObject()
    private var sheetDialog: BottomSheetDialog? = null
    private var busy = false
    private var publishBusy = false
    private var selectedPayment = "نقدی"
    private var loadGen = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        _binding = FragmentMyPropertyManageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        snapshot = MyPropertyManage.readSnapshot(arguments?.getString(ARG_SNAPSHOT))
        binding.mpManageHeader.pageTitle.text = MyPropertyManage.PAGE_TITLE
        binding.mpManageHeader.pageBack.setOnClickListener {
            if (!findNavController().navigateUp()) {
                (activity as? MainActivity)?.openMyProperty()
            }
        }
        binding.mpManagePreview.setOnClickListener { openDetail() }
        bindPreview()
        bindRows()
        loadDetail()
    }

    override fun onDestroyView() {
        sheetDialog?.dismiss()
        sheetDialog = null
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun bindPreview() {
        val b = _binding ?: return
        b.mpManageTitle.text = MyPropertyManage.titleOf(snapshot)
        val price = MyPropertyManage.priceOf(snapshot)
        b.mpManagePrice.isVisible = price > 0L
        if (price > 0L) b.mpManagePrice.text = PartnerFormat.toman(price) + " تومان"
        val published = MyPropertyManage.isPublished(snapshot)
        val status = MyPropertyManage.statusOf(snapshot)
        b.mpManageStatus.text = if (ListingArchive.isArchived(snapshot)) {
            "${ListingArchive.BADGE} · $status"
        } else {
            status
        }
        b.mpManageStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (published) R.color.vinor_accent_green else R.color.vinor_text_muted,
            ),
        )
        PartnerImages.bind(
            scope,
            b.mpManageImage,
            MyPropertyManage.imageOf(snapshot),
            b.mpManageImagePlaceholder,
        )
    }

    private fun bindRows() {
        val host = _binding?.mpManageActions ?: return
        host.removeAllViews()
        val price = MyPropertyManage.priceOf(snapshot)
        val published = MyPropertyManage.isPublished(snapshot)
        val archived = ListingArchive.isArchived(snapshot)
        MyPropertyManage.actions().forEach { action ->
            val row = ItemProfileLinkBinding.inflate(layoutInflater, host, false)
            val label = MyPropertyManage.label(
                action,
                published && action == MyPropertyManage.Action.PUBLISH,
                archived && action == MyPropertyManage.Action.ARCHIVE,
            )
            row.itemProfileLinkTitle.text = label
            val subtitle = when (action) {
                MyPropertyManage.Action.PRICE -> if (price > 0L) PartnerFormat.toman(price) + " تومان" else ""
                MyPropertyManage.Action.STATS -> ""
                else -> ""
            }
            row.itemProfileLinkSubtitle.isVisible = subtitle.isNotBlank()
            row.itemProfileLinkSubtitle.text = subtitle
            val icon = when (action) {
                MyPropertyManage.Action.VIEW -> R.drawable.ic_home
                MyPropertyManage.Action.STATS -> R.drawable.ic_commissions
                MyPropertyManage.Action.EDIT -> R.drawable.ic_clipboard
                MyPropertyManage.Action.PRICE -> R.drawable.ic_wallet
                MyPropertyManage.Action.ARCHIVE -> R.drawable.ic_lock
                MyPropertyManage.Action.SHARE -> R.drawable.ic_share_nodes
                MyPropertyManage.Action.PUBLISH -> R.drawable.ic_send_up
            }
            row.itemProfileLinkIcon.setImageResource(icon)
            val spinning = MyPropertyManage.showPublishProgress(publishBusy) && action == MyPropertyManage.Action.PUBLISH
            row.itemProfileLinkProgress.isVisible = spinning
            row.itemProfileLinkChevron.isVisible = !spinning
            row.root.isEnabled = !busy
            row.root.setOnClickListener { onAction(action) }
            row.root.contentDescription = label
            host.addView(row.root)
        }
    }

    private fun openStats() {
        (activity as? MainActivity)?.openListingStats(snapshot)
    }

    private fun onAction(action: MyPropertyManage.Action) {
        if (!MyPropertyManage.claimAction(busy)) return
        when (action) {
            MyPropertyManage.Action.VIEW -> openDetail()
            MyPropertyManage.Action.STATS -> openStats()
            MyPropertyManage.Action.EDIT -> openEdit()
            MyPropertyManage.Action.PRICE -> showPriceSheet()
            MyPropertyManage.Action.ARCHIVE -> postArchive()
            MyPropertyManage.Action.SHARE -> share()
            MyPropertyManage.Action.PUBLISH -> {
                if (MyPropertyManage.isPublished(snapshot)) showStopSheet() else showPublishSheet()
            }
        }
    }

    private fun openDetail() {
        val code = MyPropertyManage.codeOf(snapshot)
        if (code.isBlank()) {
            toast("کد ملک نامعتبر است.")
            return
        }
        (activity as? MainActivity)?.openLand(code)
    }

    private fun openEdit() {
        val code = MyPropertyManage.codeOf(snapshot)
        if (code.isBlank()) {
            toast("کد ملک نامعتبر است.")
            return
        }
        (activity as? MainActivity)?.openPropertyEdit(snapshot)
    }

    private fun share() {
        val code = MyPropertyManage.codeOf(snapshot)
        if (code.isBlank()) {
            toast("کد ملک نامعتبر است.")
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, MyPropertyManage.shareMessage(snapshot))
        }
        startActivity(Intent.createChooser(send, "اشتراک‌گذاری"))
    }

    private fun loadDetail() {
        val code = MyPropertyManage.codeOf(snapshot)
        val ctx = context ?: return
        if (code.isBlank()) return
        val path = "/express/partner/api/lands/${Uri.encode(code)}/data"
        val gen = ++loadGen
        val stamp = sessionStamp
        scope.launch {
            val wasFresh = withContext(Dispatchers.IO) { PartnerApi.isFresh(path, ctx) }
            try {
                val fresh = PartnerApi.loadDisplay(path, ctx) { data ->
                    if (_binding == null || gen != loadGen || stamp != sessionStamp) return@loadDisplay
                    if (!VinorPageLoad.canRemember(data) || !MyPropertyManage.detailAccepted(data)) return@loadDisplay
                    applyDetail(data)
                }
                if (_binding == null || gen != loadGen || stamp != sessionStamp) return@launch
                if (VinorPageLoad.needsLogin(fresh)) {
                    ListingMemory.clearSession()
                    (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    return@launch
                }
                if (VinorPageLoad.canRemember(fresh) && MyPropertyManage.detailAccepted(fresh)) {
                    applyDetail(fresh)
                } else if (VinorPageLoad.networkFailed(wasFresh, fresh)) {
                    VinorPageLoad.showOffline(binding.root) { loadDetail() }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_binding == null || gen != loadGen || stamp != sessionStamp) return@launch
                VinorPageLoad.showOffline(binding.root) { loadDetail() }
            }
        }
    }

    private fun applyDetail(fresh: JSONObject) {
        snapshot = MyPropertyManage.applyListingDetail(snapshot, fresh)
        persistSnapshot()
        bindPreview()
        bindRows()
    }

    private fun persistSnapshot() {
        arguments?.putString(ARG_SNAPSHOT, MyPropertyManage.commitSnapshot(snapshot))
    }

    private fun showPriceSheet() {
        val sheet = SheetPropertyPriceBinding.inflate(layoutInflater)
        val price = MyPropertyManage.priceOf(snapshot)
        if (price > 0L) sheet.priceSheetInput.setText(price.toString())
        val dialog = openSheet(sheet.root)
        sheet.priceSheetClose.setOnClickListener { dialog.dismiss() }
        sheet.priceSheetSave.setOnClickListener {
            val next = MyPropertyManage.priceAmount(sheet.priceSheetInput.text?.toString().orEmpty())
            if (next <= 0L) {
                toast("قیمت را وارد کنید.")
                return@setOnClickListener
            }
            dialog.dismiss()
            postPrice(next)
        }
    }

    private fun showPublishSheet() {
        val sheet = SheetPropertyPublishBinding.inflate(layoutInflater)
        val knownPay = MyPropertyManage.paymentOf(snapshot)
        if (knownPay == "نقدی" || knownPay == "توافقی" || knownPay == "معاوضه") selectedPayment = knownPay
        val knownCommission = MyPropertyManage.commissionOf(snapshot)
        if (knownCommission.isNotBlank()) sheet.publishCommission.setText(knownCommission)
        val dialog = openSheet(sheet.root)
        fun paint() {
            paintChip(sheet.publishPayCash, selectedPayment == "نقدی")
            paintChip(sheet.publishPayDeal, selectedPayment == "توافقی")
            paintChip(sheet.publishPaySwap, selectedPayment == "معاوضه")
        }
        sheet.publishPayCash.setOnClickListener { selectedPayment = "نقدی"; paint() }
        sheet.publishPayDeal.setOnClickListener { selectedPayment = "توافقی"; paint() }
        sheet.publishPaySwap.setOnClickListener { selectedPayment = "معاوضه"; paint() }
        paint()
        sheet.publishSheetClose.setOnClickListener { dialog.dismiss() }
        sheet.publishSheetSave.setOnClickListener {
            val pct = sheet.publishCommission.text?.toString()?.trim().orEmpty().ifBlank { "2" }
            dialog.dismiss()
            postPublish(selectedPayment, pct, sheet.publishSendAll.isChecked)
        }
    }

    private fun paintChip(view: TextView, on: Boolean) {
        view.setBackgroundResource(if (on) R.drawable.bg_ld_chip_on else R.drawable.bg_ld_chip)
        view.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (on) R.color.vinor_bg else R.color.vinor_text,
            ),
        )
    }

    private fun showStopSheet() {
        val sheet = SheetPropertyDeleteBinding.inflate(layoutInflater)
        val dialog = openSheet(sheet.root)
        sheet.deleteSheetTitle.text = "توقف انتشار فروش"
        sheet.deleteSheetMessage.text = "این ملک از آگهی‌های فروش برداشته می‌شود و دیگر برای بقیه دیده نمی‌شود."
        sheet.deleteSheetConfirm.text = "توقف انتشار فروش"
        sheet.deleteSheetConfirm.setTextColor(ContextCompat.getColor(requireContext(), R.color.vinor_text))
        sheet.deleteSheetCancel.setOnClickListener { dialog.dismiss() }
        sheet.deleteSheetConfirm.setOnClickListener {
            dialog.dismiss()
            postUnpublish()
        }
    }

    private fun openSheet(content: View): BottomSheetDialog {
        sheetDialog?.dismiss()
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(content)
        dialog.setOnShowListener {
            val panel = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            panel.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            BottomSheetBehavior.from(panel).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        sheetDialog = dialog
        dialog.show()
        return dialog
    }

    private fun postPrice(price: Long) {
        val body = MyPropertyManage.priceBody(snapshot, price)
        post(MyPropertyManage.SUBMIT_PATH, body, "ثبت قیمت ناموفق بود.") { fresh ->
            snapshot.put("price_total", price)
            persistSnapshot()
            bindPreview()
            bindRows()
            toast(fresh.optString("message").ifBlank { "قیمت ثبت شد." })
        }
    }

    private fun postPublish(payment: String, commission: String, sendAll: Boolean) {
        val code = MyPropertyManage.codeOf(snapshot)
        if (code.isBlank()) {
            toast("کد ملک نامعتبر است.")
            return
        }
        val body = MyPropertyManage.publishBody(code, payment, commission, sendAll)
        val ctx = context ?: return
        if (busy) return
        busy = true
        publishBusy = true
        // Ignore detail requests started before this mutation.
        ++loadGen
        bindRows()
        scope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    PartnerApi.postJson(MyPropertyManage.PUBLISH_PATH, body, ctx)
                }
                val land = MyPropertyManage.refreshAfterPublish(fresh) {
                    withContext(Dispatchers.IO) {
                        val path = MyPropertyManage.landDataPath(ListingRepository.currentCode(code).ifBlank { code })
                        ListingMemory.forgetDisk(code, ctx, path)
                        PartnerApi.get(path, ctx)
                    }
                }
                if (_binding == null) return@launch
                if (fresh.optInt("http_status") == 401) {
                    ListingMemory.clearSession()
                    toast("برای این کار وارد شوید.")
                    (activity as? MainActivity)?.openDrawer()
                    return@launch
                }
                if (MyPropertyManage.publishAccepted(fresh, land)) {
                    if (land != null) snapshot = MyPropertyManage.mergeDetail(snapshot, land)
                    MyPropertyManage.markPublished(snapshot, true)
                    ListingMemory.syncPublished(MyPropertyManage.codeOf(snapshot), true, ctx)
                    persistSnapshot()
                    bindPreview()
                    bindRows()
                    toast(fresh.optString("message").ifBlank { "ملک برای فروش منتشر شد." })
                } else if (MyPropertyManage.absorbAlreadyPublished(snapshot, fresh)) {
                    persistSnapshot()
                    bindPreview()
                    bindRows()
                    toast(fresh.optString("error").ifBlank { "این ملک قبلاً برای فروش منتشر شده است." })
                } else {
                    toast(MyPropertyManage.unpublishFailure(fresh, "انتشار ناموفق بود."))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast(e.message ?: "انتشار ناموفق بود.")
            } finally {
                busy = false
                publishBusy = false
                if (_binding != null) bindRows()
            }
        }
    }

    private fun postUnpublish() {
        val code = MyPropertyManage.codeOf(snapshot)
        val ctx = context ?: return
        if (code.isBlank()) {
            toast("کد ملک نامعتبر است.")
            return
        }
        if (busy) return
        busy = true
        publishBusy = true
        // Ignore detail requests started before this mutation.
        ++loadGen
        bindRows()
        scope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    PartnerApi.postJson(
                        MyPropertyManage.UNPUBLISH_PATH,
                        MyPropertyManage.unpublishBody(code, snapshot),
                        ctx,
                    )
                }
                val land = MyPropertyManage.refreshAfterPublish(fresh) {
                    withContext(Dispatchers.IO) {
                        val path = MyPropertyManage.landDataPath(ListingRepository.currentCode(code).ifBlank { code })
                        ListingMemory.forgetDisk(code, ctx, path)
                        PartnerApi.get(path, ctx)
                    }
                }
                if (_binding == null) return@launch
                if (fresh.optInt("http_status") == 401) {
                    ListingMemory.clearSession()
                    toast("برای این کار وارد شوید.")
                    (activity as? MainActivity)?.openDrawer()
                    return@launch
                }
                val next = MyPropertyManage.finishUnpublish(snapshot, fresh, land)
                if (next != null) {
                    snapshot = next
                    ListingMemory.syncPublished(MyPropertyManage.codeOf(snapshot), false, ctx)
                    persistSnapshot()
                    bindPreview()
                    bindRows()
                    toast(fresh.optString("message").ifBlank { "انتشار فروش متوقف شد." })
                } else {
                    toast(MyPropertyManage.unpublishFailure(fresh, "توقف انتشار ناموفق بود."), long = true)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast(e.message ?: "توقف انتشار ناموفق بود.")
            } finally {
                busy = false
                publishBusy = false
                if (_binding != null) bindRows()
            }
        }
    }

    private fun postArchive() {
        val code = MyPropertyManage.codeOf(snapshot)
        val ctx = context ?: return
        if (code.isBlank()) {
            toast("کد ملک نامعتبر است.")
            return
        }
        if (!MyPropertyManage.claimAction(busy)) return
        val restore = ListingArchive.isArchived(snapshot)
        busy = true
        bindRows()
        scope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    PartnerApi.postJson(ListingArchive.path(restore), ListingArchive.body(code), ctx)
                }
                if (_binding == null) return@launch
                when (ListingArchive.outcome(fresh)) {
                    ListingArchive.Outcome.UNAUTHORIZED -> {
                        ListingMemory.clearSession()
                        toast(ListingArchive.message(fresh, "برای این کار وارد شوید."))
                        (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    }
                    ListingArchive.Outcome.NOT_FOUND -> {
                        ListingMemory.invalidateLand(code, ctx)
                        MyPropertiesFragment.clearRemembered()
                        NativePartnerFragment.forgetPage(NativePartnerFragment.PAGE_LISTINGS)
                        toast(ListingArchive.message(fresh, "این آگهی پیدا نشد."))
                        if (!findNavController().navigateUp()) {
                            (activity as? MainActivity)?.openMyProperty()
                        }
                    }
                    ListingArchive.Outcome.OK -> {
                        forgetListingLists(ctx)
                        snapshot = ListingArchive.applyResult(snapshot, fresh)
                        persistSnapshot()
                        bindPreview()
                        bindRows()
                        toast(ListingArchive.message(fresh, if (restore) "آگهی به فهرست فعال برگشت." else "آگهی بایگانی شد."))
                    }
                    ListingArchive.Outcome.FORBIDDEN,
                    ListingArchive.Outcome.OFFLINE,
                    ListingArchive.Outcome.FAILED,
                    -> toast(ListingArchive.message(fresh, "بایگانی آگهی انجام نشد."), long = true)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast(e.message ?: "بایگانی آگهی انجام نشد.")
            } finally {
                busy = false
                if (_binding != null) bindRows()
            }
        }
    }

    private fun forgetListingLists(ctx: android.content.Context) {
        ListingArchive.cachePaths().forEach { PartnerApi.forget(it, ctx) }
        MyPropertiesFragment.clearRemembered()
        NativePartnerFragment.forgetPage(NativePartnerFragment.PAGE_LISTINGS)
    }

    private fun post(path: String, body: JSONObject, failure: String, onOk: (JSONObject) -> Unit) {
        val ctx = context ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) { PartnerApi.postJson(path, body, ctx) }
                if (_binding == null) return@launch
                if (fresh.optInt("http_status") == 401) {
                    ListingMemory.clearSession()
                    toast("برای این کار وارد شوید.")
                    (activity as? MainActivity)?.openDrawer()
                    return@launch
                }
                if (fresh.optBoolean("ok", false)) {
                    onOk(fresh)
                } else {
                    toast(MyPropertyManage.failureMessage(fresh, failure))
                }
            } catch (e: Exception) {
                toast(e.message ?: failure)
            } finally {
                busy = false
            }
        }
    }

    private fun toast(msg: String, long: Boolean = false) {
        val ctx = context ?: return
        Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ARG_SNAPSHOT = "snapshot"
        private var sessionStamp = 0

        fun clearRemembered() {
            sessionStamp++
        }
    }
}
