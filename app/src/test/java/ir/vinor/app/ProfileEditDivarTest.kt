package ir.vinor.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ویرایش پروفایل به سبک دیوار: همان هدر پیگیری/مشتریان، کارت‌های لیست،
 * فیلدهای خط‌زیر، نوار ذخیرهٔ چسبان، و شمارهٔ فقط‌خواندنی.
 */
class ProfileEditDivarTest {
    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val d = dir ?: return@repeat
            listOf(File(d, "src/main"), File(d, "app/src/main")).firstOrNull {
                File(it, "AndroidManifest.xml").isFile
            }?.let { return it }
            dir = d.parentFile
        }
        throw AssertionError("app/src/main not found")
    }

    private fun layout(name: String) = root().resolve("res/layout/$name.xml").readText()
    private fun src(name: String) = root().resolve("java/ir/vinor/app/$name").readText()

    @Test
    fun headerMatchesCustomersAndTracking() {
        val edit = layout("fragment_profile_edit")
        val customers = layout("fragment_customers")
        val tracking = layout("include_page_header")
        assertTrue(edit.contains("android:id=\"@+id/profileEditHeader\""))
        assertTrue(edit.contains("android:minHeight=\"56dp\""))
        assertTrue(edit.contains("android:background=\"@color/vinor_chrome\""))
        assertTrue(edit.contains("android:layout_height=\"1dp\"") && edit.contains("@color/vinor_border"))
        assertTrue(edit.contains("@font/vazirmatn_bold"))
        assertTrue(edit.contains("android:textSize=\"17sp\""))
        assertTrue(customers.contains("android:minHeight=\"56dp\""))
        assertTrue(customers.contains("android:background=\"@color/vinor_chrome\""))
        assertTrue(tracking.contains("android:minHeight=\"56dp\""))
        assertTrue(tracking.contains("android:background=\"@color/vinor_chrome\""))
        // هدر بیرون از ScrollView می‌ماند.
        val headerAt = edit.indexOf("android:id=\"@+id/profileEditHeader\"")
        val scrollAt = edit.indexOf("android:id=\"@+id/profileEditScroll\"")
        assertTrue(headerAt in 1 until scrollAt)
    }

    @Test
    fun formHasDivarRowsAndStickySave() {
        val edit = layout("fragment_profile_edit")
        assertTrue(edit.contains("android:scrollbars=\"none\""))
        assertTrue(edit.contains("@drawable/bg_pe_card"))
        assertTrue(edit.contains("@+id/profileEditPhone"))
        assertTrue(edit.contains("شماره موبایل قابل تغییر نیست"))
        assertTrue(edit.contains("@+id/profileEditNameError"))
        assertTrue(edit.contains("@+id/profileEditSaveBar"))
        val scrollAt = edit.indexOf("android:id=\"@+id/profileEditScroll\"")
        val saveBarAt = edit.indexOf("android:id=\"@+id/profileEditSaveBar\"")
        val saveAt = edit.indexOf("android:id=\"@+id/profileEditSave\"")
        assertTrue(saveBarAt > scrollAt && saveAt > saveBarAt)
        assertTrue(edit.contains("@drawable/bg_pe_save"))
        assertTrue(edit.contains("ذخیره تغییرات"))
        assertFalse(edit.contains("تنظیمات پروفایل"))
        for (id in listOf(
            "profileEditBack", "profileEditProgress", "profileEditAvatarTrigger",
            "profileEditAvatar", "profileEditInitials", "profileEditAvatarUploading",
            "profileEditName", "profileEditCity", "profileEditBio", "profileEditBioCount",
            "profileEditCancel", "profileEditSave",
        )) {
            assertTrue(id, edit.contains("@+id/$id\""))
        }
    }

    @Test
    fun backAndValidationStayWired() {
        val kt = src("ProfileEditFragment.kt")
        assertTrue(kt.contains("binding.profileEditBack.setOnClickListener"))
        assertTrue(kt.contains("findNavController().navigateUp()"))
        assertTrue(kt.contains("profileEditPhone"))
        assertTrue(kt.contains("profileEditNameError"))
        assertTrue(kt.contains("نام را وارد کنید") || kt.contains("profileEditNameError.visibility = View.VISIBLE"))
        assertTrue(kt.contains("binding.profileEditSave.setOnClickListener { save() }"))
    }
}
