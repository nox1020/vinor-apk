package ir.vinor.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import ir.vinor.app.databinding.FragmentProfileBinding
import ir.vinor.app.databinding.ItemProfileLinkBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.webkit.CookieManager

/**
 * تب «وینور من». مهمان فقط با پاسخ ۲۰۰ و شمارهٔ خالی است؛ خطای شبکه هرگز مهمان نیست.
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)

    private var latestProfile: JSONObject? = null
    private var loadGen = 0
    private var crmFetchGen = -1
    private var unreadCount = 0
    private var rendered = false
    private var skipNextResume = true
    private var shimmer: ValueAnimator? = null
    private var shimmerPrimed = false
    private var summaryFetchGen = -1

    private sealed class ViewState {
        data object Loading : ViewState()
        data class Guest(val profile: VinorManMenu.Profile) : ViewState()
        data class Content(
            val profile: VinorManMenu.Profile,
            val name: String,
            val maskedPhone: String,
            val avatarUrl: String?,
        ) : ViewState()

        data object Error : ViewState()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        skipNextResume = true
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.profileAvatarFrame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.profileAvatarFrame.clipToOutline = true
        binding.profileNotifications.setOnClickListener {
            if (PartnerApi.hasSession()) (activity as? MainActivity)?.openNotifications()
            else openLoginScreen()
        }
        binding.profileSummaryWallet.setOnClickListener {
            (activity as? MainActivity)?.openPath("/express/partner/commissions")
        }
        binding.profileQuickFollow.setOnClickListener {
            val path = if (latestProfile?.optBoolean("is_approved") == true) {
                "/express/partner/requests"
            } else {
                "/express/partner/levels"
            }
            (activity as? MainActivity)?.openPath(path)
        }
        binding.profileGuestLogin.setOnClickListener { openLoginScreen() }
        binding.profileRetry.setOnClickListener { loadData(forceSkeleton = true) }
        rememberedProfile?.let { applyPayload(it, loadGen) }
        loadData(forceSkeleton = !rendered && rememberedProfile == null)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        val snap = rememberedProfile ?: return
        if (_binding == null) return
        applyPayload(snap, loadGen)
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResume) {
            skipNextResume = false
            return
        }
        if (_binding != null) loadData(forceSkeleton = !rendered)
    }

    override fun onDestroyView() {
        stopShimmer()
        shimmerPrimed = false
        summaryFetchGen = -1
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun loadData(forceSkeleton: Boolean) {
        val ctx = context ?: return
        if (_binding == null) return
        val gen = ++loadGen
        val stamp = sessionStamp
        if (forceSkeleton || !rendered) render(ViewState.Loading)
        scope.launch {
            val wasFresh = withContext(Dispatchers.IO) { PartnerApi.isFresh(PROFILE_DATA, ctx) }
            var showedCache = false
            try {
                val obj = PartnerApi.loadDisplay(PROFILE_DATA, ctx) { data ->
                    if (gen != loadGen || stamp != sessionStamp || _binding == null) return@loadDisplay
                    if (applyPayload(data, gen)) showedCache = true
                }
                if (gen != loadGen || stamp != sessionStamp || _binding == null) return@launch
                applyPayload(obj, gen)
                val renderedPayload = isGuest(obj) || isContent(obj)
                val networkFailed = !wasFresh && (
                    obj.optBoolean("from_cache") ||
                        obj.optBoolean("offline") ||
                        (obj.has("http_status") && obj.optInt("http_status") !in 200..299)
                    )
                when {
                    renderedPayload && networkFailed -> showOfflineSnackbar()
                    renderedPayload -> Unit
                    showedCache -> showOfflineSnackbar()
                    else -> render(ViewState.Error)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (gen != loadGen || stamp != sessionStamp || _binding == null) return@launch
                if (showedCache || rendered) showOfflineSnackbar() else render(ViewState.Error)
            }
        }
    }

    private fun applyPayload(obj: JSONObject, gen: Int): Boolean {
        if (isGuest(obj)) {
            latestProfile = null
            unreadCount = 0
            val profile = menuProfile(obj, crm = false, unread = 0)
            render(ViewState.Guest(profile))
            return true
        }
        if (!isContent(obj)) return false
        val copy = JSONObject(obj.toString())
        copy.remove("from_cache")
        rememberedProfile = copy
        latestProfile = copy
        val crm = customersMenuVisible(obj)
        val profile = menuProfile(obj, crm, unreadCount)
        val name = obj.optString("me_name", "").trim()
        render(
            ViewState.Content(
                profile = profile,
                name = name,
                maskedPhone = VinorManMenu.maskPhone(obj.optString("me_phone", "")),
                avatarUrl = obj.optString("avatar_url", "").trim().takeIf { it.isNotEmpty() },
            ),
        )
        refreshUnread(gen, profile, name)
        refreshWorkSummary(gen, obj)
        return true
    }

    /** Live account figures use the same home/data contract as the former home dashboard. */
    private fun refreshWorkSummary(gen: Int, profile: JSONObject) {
        val b = _binding ?: return
        val approved = profile.optBoolean("is_approved")
        b.profileSummaryWallet.isVisible = approved
        b.profileSummaryPerformance.isVisible = approved
        // An approved partner already has Follow-up in the menu and bottom bar.
        // Keep the unique Levels shortcut for non-approved accounts.
        b.profileQuickFollow.isVisible = !approved
        b.profileQuickFollow.text = "سطوح"
        if (!approved || summaryFetchGen == gen) return
        summaryFetchGen = gen
        val currentStamp = sessionStamp
        val ctx = context ?: return
        scope.launch {
            val home = withContext(Dispatchers.IO) {
                runCatching { PartnerApi.get(HOME_DATA, ctx) }.getOrNull()
            } ?: return@launch
            if (_binding == null || gen != loadGen || sessionStamp != currentStamp) return@launch
            val code = if (home.has("http_status")) home.optInt("http_status") else 200
            if (code !in 200..299 || home.optBoolean("offline") || home.optBoolean("session_expired")) return@launch
            if (!home.optBoolean("success") && !home.has("is_approved")) return@launch
            val profilePhone = profile.optString("me_phone").trim()
            val homePhone = home.optString("me_phone").trim()
            if (homePhone.isNotEmpty() && profilePhone.isNotEmpty() && homePhone != profilePhone) return@launch
            val current = _binding ?: return@launch
            current.profileSummaryAmount.text = PartnerFormat.toman(HomePulse.withdrawable(home)) + " تومان"
            current.profileSummaryCustomers.text = PartnerFormat.toman(HomePulse.customers(home))
            current.profileSummaryVisits.text = PartnerFormat.toman(HomePulse.visits(home))
            current.profileSummaryDeals.text = PartnerFormat.toman(HomePulse.deals(home))
        }
    }

    private fun menuProfile(obj: JSONObject, crm: Boolean, unread: Int): VinorManMenu.Profile {
        val apkUrl = obj.optString("android_apk_url", "").trim()
        val apkVersion = obj.optString("android_apk_version", "").trim()
        val showUpdate = obj.optBoolean("show_app_update", false) ||
            (apkUrl.isNotEmpty() && apkVersion.isNotEmpty() &&
                ApkVersionUtils.isServerNewer(apkVersion, appVersionName()))
        return VinorManMenu.Profile(
            loggedIn = obj.optString("me_phone", "").trim().isNotEmpty(),
            approved = obj.optBoolean("is_approved", false),
            pending = obj.optBoolean("has_pending_app", false),
            plusEnabled = obj.optBoolean("plus_system_enabled", false),
            lawyerOn = obj.optBoolean("lawyer_feature_on", false),
            crmUiEnabled = crm,
            showAppUpdate = showUpdate && apkUrl.isNotEmpty(),
            unreadCount = unread,
        )
    }

    private fun isGuest(obj: JSONObject): Boolean {
        if (obj.optBoolean("offline")) return false
        if (!obj.has("http_status") || obj.optInt("http_status") != 200) return false
        return obj.optString("me_phone", "").trim().isEmpty()
    }

    private fun isContent(obj: JSONObject): Boolean {
        if (obj.optString("me_phone", "").trim().isEmpty()) return false
        if (obj.optBoolean("offline")) return false
        val code = if (obj.has("http_status")) obj.optInt("http_status") else 200
        return code == 200
    }

    private fun render(state: ViewState) {
        val b = _binding ?: return
        val account = (state as? ViewState.Content)?.profile
        val unread = if (account?.loggedIn == true) account.unreadCount.coerceAtLeast(0) else 0
        b.profileNotificationsBadge.isVisible = unread > 0
        b.profileNotificationsBadge.text = if (unread > 99) "۹۹+" else VinorManMenu.persianDigits(unread)
        b.profileNotifications.contentDescription = if (account?.loggedIn == true) {
            "اعلان‌ها، ${VinorManMenu.persianDigits(unread)} خوانده‌نشده"
        } else {
            "اعلان‌ها"
        }
        when (state) {
            ViewState.Loading -> {
                stopShimmer()
                b.profileSkeleton.isVisible = true
                b.profileGuest.isVisible = false
                b.profileError.isVisible = false
                b.profileRow.isVisible = false
                b.profileWorkSummary.isVisible = false
                b.profileMenu.isVisible = false
                startShimmer()
            }
            is ViewState.Guest -> {
                stopShimmer()
                rendered = true
                b.profileSkeleton.isVisible = false
                b.profileGuest.isVisible = true
                b.profileError.isVisible = false
                b.profileRow.isVisible = false
                b.profileWorkSummary.isVisible = false
                b.profileMenu.isVisible = true
                renderRows(VinorManMenu.rows(state.profile, PHASE_A_FLAGS), state.profile)
            }
            is ViewState.Content -> {
                stopShimmer()
                rendered = true
                b.profileSkeleton.isVisible = false
                b.profileGuest.isVisible = false
                b.profileError.isVisible = false
                b.profileRow.isVisible = true
                b.profileWorkSummary.isVisible = true
                b.profileMenu.isVisible = true
                b.profileName.text = state.name
                b.profilePhone.text = state.maskedPhone
                b.profileInitials.text = VinorManMenu.avatarLetter(state.name)
                b.profileInitials.isVisible = true
                b.profileAvatar.isVisible = false
                b.profileAvatar.setImageDrawable(null)
                b.profileRow.contentDescription = "ویرایش پروفایل، ${state.name}"
                b.profileRow.setOnClickListener { openProfileEdit() }
                if (!state.avatarUrl.isNullOrEmpty()) loadAvatar(state.avatarUrl)
                renderRows(VinorManMenu.rows(state.profile, PHASE_A_FLAGS), state.profile)
            }
            ViewState.Error -> {
                stopShimmer()
                b.profileSkeleton.isVisible = false
                b.profileGuest.isVisible = false
                b.profileError.isVisible = true
                b.profileRow.isVisible = false
                b.profileWorkSummary.isVisible = false
                b.profileMenu.isVisible = true
                renderRows(VinorManMenu.supportRows(), VinorManMenu.Profile(loggedIn = false))
            }
        }
    }

    private fun renderRows(rows: List<VinorManMenu.Row>, profile: VinorManMenu.Profile) {
        val menu = binding.profileMenu
        menu.removeAllViews()
        var lastGroup = -1
        for (row in rows) {
            if (row.group != lastGroup) {
                if (lastGroup != -1) menu.addView(groupBar())
                VinorManMenu.groupTitle(row.group)?.let { menu.addView(groupHeader(it)) }
                lastGroup = row.group
            }
            menu.addView(rowView(row, profile))
        }
    }

    private fun groupBar(): View {
        return View(requireContext()).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.vinor_raised))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8),
            )
        }
    }

    private fun groupHeader(title: String): TextView {
        return TextView(requireContext()).apply {
            text = title
            textSize = 12f
            typeface = ResourcesCompat.getFont(context, R.font.vazirmatn_bold)
            setTextColor(ContextCompat.getColor(context, R.color.vinor_text_muted))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            val h = dp(16)
            setPadding(h, dp(12), h, dp(8))
        }
    }

    private fun rowView(row: VinorManMenu.Row, profile: VinorManMenu.Profile): View {
        val item = ItemProfileLinkBinding.inflate(layoutInflater, binding.profileMenu, false)
        item.itemProfileLinkTitle.text = row.label
        val subtitle = row.subtitle.orEmpty()
        item.itemProfileLinkSubtitle.text = subtitle
        item.itemProfileLinkSubtitle.isVisible = subtitle.isNotBlank()
        val icon = iconFor(row)
        val logout = icon == null || row.action == VinorManMenu.Action.LOGOUT
        if (logout || icon == null) {
            item.itemProfileLinkIcon.isVisible = false
            item.itemProfileLinkChevron.isVisible = false
            item.itemProfileLinkTitle.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.vinor_primary_red),
            )
        } else {
            item.itemProfileLinkIcon.isVisible = true
            item.itemProfileLinkIcon.setImageResource(icon)
            item.itemProfileLinkChevron.isVisible = true
        }
        (item.itemProfileLinkTitle.parent as? View)?.let { texts ->
            val lp = texts.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                lp.marginStart = if (logout) 0 else dp(12)
                texts.layoutParams = lp
            }
        }
        val badge = row.badge
        item.itemProfileLinkBadge.isVisible = !badge.isNullOrBlank()
        item.itemProfileLinkBadge.text = badge.orEmpty()
        if (row.id == "notifications") {
            item.root.contentDescription =
                "اعلان‌ها، ${VinorManMenu.persianDigits(profile.unreadCount)} خوانده‌نشده"
            markChildrenUnimportant(item.root)
        }
        item.root.setOnClickListener { onRow(row) }
        return item.root
    }

    private fun onRow(row: VinorManMenu.Row) {
        val loggedIn = latestProfile?.optString("me_phone", "")?.trim()?.isNotEmpty() == true &&
            _binding?.profileRow?.isVisible == true
        if (row.requiresLogin && !loggedIn) {
            val path = row.destination
            val ctx = context
            if (ctx != null && path != null && VinorManMenu.isSafeInternalPath(path)) {
                DeepLinkPendingStore.save(ctx, path)
            }
            openLoginScreen()
            return
        }
        val host = activity as? MainActivity
        when (row.action) {
            VinorManMenu.Action.MY_PROPERTY -> host?.openMyProperty()
            VinorManMenu.Action.FAVORITES -> host?.openPath("/express/partner/favorites")
            VinorManMenu.Action.NOTIFICATIONS -> host?.openNotifications()
            VinorManMenu.Action.CONSULTANT -> host?.openConsultantOnboarding()
            VinorManMenu.Action.REQUESTS -> host?.openPath("/express/partner/requests")
            VinorManMenu.Action.CUSTOMERS -> host?.openPath("/express/partner/customers")
            VinorManMenu.Action.WALLET -> host?.openPath("/express/partner/commissions")
            VinorManMenu.Action.INVITE -> host?.openPath("/express/partner/invite")
            VinorManMenu.Action.TRAINING -> host?.openPath("/express/partner/training")
            VinorManMenu.Action.PLUS -> host?.openPath("/express/partner/plus")
            VinorManMenu.Action.SETTINGS -> host?.openPath("/express/partner/settings")
            VinorManMenu.Action.SUPPORT -> host?.openVinorChat()
            VinorManMenu.Action.LAWYER -> host?.openVinorChat()
            VinorManMenu.Action.TERMS -> host?.openPath("/express/partner/legal/terms")
            VinorManMenu.Action.APP_UPDATE -> startApkUpdate()
            VinorManMenu.Action.LOGOUT -> confirmLogout()
        }
    }

    private fun iconFor(row: VinorManMenu.Row): Int? = when (row.id) {
        "my_property" -> R.drawable.ic_home
        "favorites" -> R.drawable.ic_bookmark
        "notifications" -> R.drawable.ic_mail
        "consultant" -> R.drawable.ic_person_add
        "consultant_status" -> R.drawable.ic_clock
        "requests" -> R.drawable.ic_routine
        "customers" -> R.drawable.ic_users_stroke
        "wallet" -> R.drawable.ic_wallet
        "invite" -> R.drawable.ic_person_add
        "invite_friends" -> R.drawable.ic_share_nodes
        "training" -> R.drawable.ic_graduation
        "plus" -> R.drawable.ic_sparkle
        "settings" -> R.drawable.ic_settings
        "support" -> R.drawable.ic_help
        "lawyer" -> R.drawable.ic_scale
        "terms" -> R.drawable.ic_clipboard
        "app_update" -> R.drawable.ic_about
        else -> null
    }

    private fun confirmLogout() {
        VinorLogoutSheet.show(this) { finish ->
            doLogout(finish)
        }
    }

    private fun doLogout(onFinished: (() -> Unit)? = null) {
        val host = activity as? MainActivity
        if (host != null) {
            host.logoutFromDrawer(onFinished)
            return
        }
        val ctx = context ?: run {
            onFinished?.invoke()
            return
        }
        VinorSessionLogout.perform(ctx, scope) {
            try {
                findNavController().navigate(R.id.dashboardFragment)
            } catch (_: Exception) {
            } finally {
                onFinished?.invoke()
            }
        }
    }

    private fun openLoginScreen() {
        try {
            findNavController().navigate(R.id.action_profile_to_login)
        } catch (_: Exception) {
            (activity as? MainActivity)?.openLogin()
        }
    }

    private fun openProfileEdit() {
        try {
            findNavController().navigate(R.id.action_profile_to_profileEdit)
        } catch (_: Exception) {
            (activity as? MainActivity)?.openProfileEdit()
        }
    }

    private fun flagOrNull(obj: JSONObject?, key: String): Boolean? {
        if (obj == null || !obj.has(key)) return null
        return obj.optBoolean(key, false)
    }

    private fun customersMenuVisible(profile: JSONObject): Boolean {
        if (!profile.optBoolean("is_approved", false)) return false
        val profileFlag = flagOrNull(profile, PartnerCrm.FLAG)
        val ctx = context
        val home = if (ctx != null && profileFlag == null) PartnerApi.peekJson(HOME_DATA, ctx) else null
        val homeFlag = flagOrNull(home, PartnerCrm.FLAG)
        val homeFresh = profileFlag == null && ctx != null && homeFlag != null &&
            PartnerApi.isFresh(HOME_DATA, ctx)
        val decision = PartnerCrm.decide(
            isApproved = true,
            profileFlag = profileFlag,
            homeFlag = homeFlag,
            homeFresh = homeFresh,
            remembered = rememberedCustomers,
        )
        rememberedCustomers = decision.visible
        if (decision.refresh) refreshCrmFromHome(profile, decision.visible)
        return decision.visible
    }

    private fun refreshCrmFromHome(profile: JSONObject, shown: Boolean) {
        val ctx = context ?: return
        val gen = loadGen
        if (crmFetchGen == gen) return
        crmFetchGen = gen
        scope.launch {
            val home = withContext(Dispatchers.IO) {
                runCatching { PartnerApi.get(HOME_DATA, ctx) }.getOrNull()
            } ?: return@launch
            if (gen != loadGen || _binding == null) return@launch
            val visible = PartnerCrm.showMenu(
                profile.optBoolean("is_approved", false),
                null,
                flagOrNull(home, PartnerCrm.FLAG),
            )
            rememberedCustomers = visible
            if (visible == shown) return@launch
            latestProfile?.let { applyPayload(it, gen) }
        }
    }

    private fun refreshUnread(gen: Int, profile: VinorManMenu.Profile, name: String) {
        if (!profile.loggedIn) return
        val ctx = context ?: return
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val obj = PartnerApi.get(UNREAD, ctx)
                    if (obj.optInt("http_status") !in 200..299) return@runCatching null
                    obj.optInt("unread_count", 0)
                }.getOrNull()
            } ?: return@launch
            if (gen != loadGen || _binding == null || count == unreadCount) return@launch
            unreadCount = count
            val current = latestProfile ?: return@launch
            if (!isContent(current)) return@launch
            val crm = customersMenuVisible(current)
            render(
                ViewState.Content(
                    profile = menuProfile(current, crm, unreadCount),
                    name = name,
                    maskedPhone = VinorManMenu.maskPhone(current.optString("me_phone", "")),
                    avatarUrl = current.optString("avatar_url", "").trim().takeIf { it.isNotEmpty() },
                ),
            )
        }
    }

    private fun loadAvatar(path: String) {
        val full = VinorAvatarCache.resolve(path)
        if (full.isEmpty()) return
        VinorAvatarCache.peek(full)?.let { instant ->
            binding.profileAvatar.setImageBitmap(instant)
            binding.profileAvatar.isVisible = true
            binding.profileInitials.isVisible = false
            return
        }
        val ctx = context ?: return
        scope.launch {
            try {
                val cookie = runCatching { CookieManager.getInstance().getCookie(BASE) }.getOrNull().orEmpty()
                val bytes = withContext(Dispatchers.IO) {
                    VinorAvatarCache.read(ctx, full)
                        ?: VinorAvatarCache.fetch(full, cookie)?.also { fresh ->
                            VinorAvatarCache.store(ctx, full, fresh)
                        }
                } ?: return@launch
                val bmp = withContext(Dispatchers.IO) { VinorAvatarCache.decode(bytes) } ?: return@launch
                VinorAvatarCache.rememberBitmap(full, bmp)
                if (_binding == null || !binding.profileRow.isVisible) return@launch
                binding.profileAvatar.setImageBitmap(bmp)
                binding.profileAvatar.isVisible = true
                binding.profileInitials.isVisible = false
            } catch (_: Exception) {
            }
        }
    }

    private fun startApkUpdate() {
        val ctx = context ?: return
        if (!StoreListing.allowsPackageInstall()) {
            StoreListing.openListing(ctx)
            return
        }
        val state = VinorAppUpdateController.read(ctx.applicationContext)
        if (state.status == VinorAppUpdateStatus.READY) {
            VinorApkUpdateInstaller.install(ctx)
            return
        }
        if (state.show) VinorAppUpdateController.retryDownload(ctx.applicationContext)
        else VinorAppUpdateController.check(ctx.applicationContext, force = true)
    }

    private fun showOfflineSnackbar() {
        val b = _binding ?: return
        val bar = Snackbar.make(b.root, "اتصال برقرار نشد", Snackbar.LENGTH_LONG)
        bar.setAction("تلاش دوباره") { loadData(forceSkeleton = false) }
        bar.setActionTextColor(ContextCompat.getColor(requireContext(), R.color.vinor_on_primary))
        bar.view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.vinor_raised))
        bar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.let { text ->
            text.setTextColor(ContextCompat.getColor(requireContext(), R.color.vinor_text))
            text.typeface = ResourcesCompat.getFont(requireContext(), R.font.vazirmatn)
        }
        bar.show()
    }

    private fun startShimmer() {
        if (shimmer != null || _binding == null) return
        val blocks = skeletonBlocks()
        if (!shimmerPrimed) {
            blocks.forEach { it.background = it.background?.mutate() }
            shimmerPrimed = true
        }
        val base = ContextCompat.getColor(requireContext(), R.color.vinor_raised)
        val hi = ContextCompat.getColor(requireContext(), R.color.vinor_raised_2)
        if (!motionEnabled()) {
            paintSkeleton(blocks, base)
            return
        }
        shimmer = ValueAnimator.ofArgb(base, hi).apply {
            duration = 1200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim -> paintSkeleton(blocks, anim.animatedValue as Int) }
            start()
        }
    }

    private fun stopShimmer() {
        shimmer?.cancel()
        shimmer = null
    }

    private fun paintSkeleton(blocks: List<View>, color: Int) {
        blocks.forEach { view ->
            val bg = view.background
            if (bg is GradientDrawable) bg.setColor(color) else view.setBackgroundColor(color)
        }
    }

    private fun skeletonBlocks(): List<View> {
        val root = _binding?.profileSkeleton ?: return emptyList()
        val out = ArrayList<View>()
        fun walk(view: View) {
            if (view.tag == "sk") out.add(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    private fun motionEnabled(): Boolean {
        return try {
            Settings.Global.getFloat(requireContext().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        } catch (_: Exception) {
            true
        }
    }

    private fun markChildrenUnimportant(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (child is ViewGroup) markChildrenUnimportant(child)
        }
    }

    private fun appVersionName(): String = try {
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName?.trim().orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BASE = "https://vinor.ir"
        private const val PROFILE_DATA = "/express/partner/profile/data"
        private const val PROFILE_EDIT_DATA = "/express/partner/profile/edit/data"
        private const val HOME_DATA = "/express/partner/home/data"
        private const val UNREAD = "/express/partner/api/notifications/unread-count"
        private val PHASE_A_FLAGS = VinorManMenu.Flags(consultantApiEnabled = true)

        private var rememberedProfile: JSONObject? = null
        private var rememberedCustomers: Boolean? = null
        private var sessionStamp = 0

        fun clearRemembered() {
            rememberedProfile = null
            rememberedCustomers = null
            sessionStamp++
        }

        /** نام یا عکس تازه را روی کش پروفایل می‌نویسد تا برگشت به این صفحه نسخهٔ قدیمی را نشان ندهد. */
        fun noteLocalEdit(context: Context, name: String? = null, avatarUrl: String? = null) {
            if (name == null && avatarUrl.isNullOrEmpty()) return
            patchCached(context, PROFILE_DATA, name, avatarUrl, keep = true)
            patchCached(context, PROFILE_EDIT_DATA, name, avatarUrl, keep = false)
        }

        private fun patchCached(
            context: Context,
            path: String,
            name: String?,
            avatarUrl: String?,
            keep: Boolean,
        ) {
            val current = PartnerApi.peekJson(path, context)
            val source = current ?: (if (keep) rememberedProfile else null) ?: return
            val obj = JSONObject(source.toString())
            obj.remove("from_cache")
            if (name != null) obj.put("me_name", name)
            if (!avatarUrl.isNullOrEmpty()) obj.put("avatar_url", avatarUrl)
            if (keep) rememberedProfile = JSONObject(obj.toString())
            PartnerApi.remember(path, obj, context)
        }
    }
}

/** ردیف‌های منو به نام، شماره و عکس وابسته نیستند تا عوض شدنشان کل لیست را از نو نسازد. */
internal object ProfileMenu {
    fun rowsKey(
        isApproved: Boolean,
        isSpecial: Boolean,
        hasPendingApp: Boolean,
        plusOn: Boolean,
        lawyerOn: Boolean,
        showAppUpdate: Boolean,
        apkUrl: String,
        apkVersion: String,
        showCustomers: Boolean,
    ): String = listOf(
        "user",
        isApproved,
        isSpecial,
        hasPendingApp,
        plusOn,
        lawyerOn,
        showAppUpdate,
        apkUrl,
        apkVersion,
        showCustomers,
    ).joinToString("\u0001")

    fun guestKey(plusOn: Boolean, lawyerOn: Boolean): String =
        listOf("guest", plusOn, lawyerOn).joinToString("\u0001")
}
