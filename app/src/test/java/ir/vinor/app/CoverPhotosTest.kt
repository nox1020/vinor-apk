package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPhotosTest {

    private fun slot(path: String) = CoverPhotos.Slot(path, CoverPhotos.displayUrl(path), path.substringAfterLast('/'))

    @Test
    fun emptyPayloadHasNoCover() {
        assertTrue(CoverPhotos.slotsFrom(null).isEmpty())
        assertTrue(CoverPhotos.slotsFrom(JSONObject()).isEmpty())
        assertTrue(CoverPhotos.slotsFrom(JSONObject().put("images", JSONArray())).isEmpty())
    }

    @Test
    fun stringImagesKeepOrderAndStripDisplayQuery() {
        val obj = JSONObject().put(
            "images",
            JSONArray()
                .put("/uploads/a.jpg?w=1280&q=82")
                .put("uploads/b.jpg")
                .put("https://cdn.example/c.jpg"),
        )
        val slots = CoverPhotos.slotsFrom(obj)
        assertEquals(listOf("uploads/a.jpg", "uploads/b.jpg", "https://cdn.example/c.jpg"), slots.map { it.path })
        assertEquals("/uploads/a.jpg?w=1280&q=82", slots[0].url)
        assertEquals(listOf("uploads/a.jpg", "uploads/b.jpg", "https://cdn.example/c.jpg"), pathsOf(slots))
    }

    @Test
    fun objectImagesReadPathUrlAndSrc() {
        val obj = JSONObject().put(
            "images",
            JSONArray()
                .put(JSONObject().put("path", "uploads/a.jpg").put("url", "/uploads/a.jpg").put("name", "نما"))
                .put(JSONObject().put("src", "/uploads/b.jpg"))
                .put(JSONObject().put("url", "uploads/a.jpg")),
        )
        val slots = CoverPhotos.slotsFrom(obj)
        assertEquals(listOf("uploads/a.jpg", "uploads/b.jpg"), slots.map { it.path })
        assertEquals("نما", slots[0].name)
        assertEquals("/uploads/b.jpg", slots[1].url)
    }

    @Test
    fun coverFieldsFillInWhenGalleryMissing() {
        val cover = CoverPhotos.slotsFrom(JSONObject().put("cover_image", "/uploads/cover.jpg"))
        assertEquals("uploads/cover.jpg", cover.single().path)
        val url = CoverPhotos.slotsFrom(JSONObject().put("image_url", "/uploads/hero.jpg"))
        assertEquals("uploads/hero.jpg", url.single().path)
        val both = CoverPhotos.slotsFrom(
            JSONObject()
                .put("images", JSONArray().put("uploads/first.jpg"))
                .put("image_url", "/uploads/other.jpg"),
        )
        assertEquals("uploads/first.jpg", both.single().path)
    }

    @Test
    fun withCoverMovesChosenPhotoFirst() {
        val slots = listOf(slot("uploads/a.jpg"), slot("uploads/b.jpg"), slot("uploads/c.jpg"))
        assertEquals(listOf("uploads/b.jpg", "uploads/a.jpg", "uploads/c.jpg"), CoverPhotos.withCover(slots, 1).map { it.path })
        assertEquals(slots.map { it.path }, CoverPhotos.withCover(slots, 0).map { it.path })
        assertEquals(slots.map { it.path }, CoverPhotos.withCover(slots, -1).map { it.path })
        assertEquals(slots.map { it.path }, CoverPhotos.withCover(slots, 9).map { it.path })
        assertTrue(CoverPhotos.withCover(emptyList(), 0).isEmpty())
    }

    @Test
    fun submitSendsImagesForCreateAndOnlyChangedEdit() {
        assertTrue(CoverPhotos.includeOnSubmit(editing = false, changed = false, count = 0))
        assertTrue(CoverPhotos.includeOnSubmit(editing = false, changed = true, count = 2))
        assertFalse(CoverPhotos.includeOnSubmit(editing = true, changed = false, count = 3))
        assertFalse(CoverPhotos.includeOnSubmit(editing = true, changed = true, count = 0))
        assertTrue(CoverPhotos.includeOnSubmit(editing = true, changed = true, count = 2))
    }

    @Test
    fun detailCapMatchesServerGalleryLimit() {
        assertFalse(CoverPhotos.atDetailCap(7))
        assertTrue(CoverPhotos.atDetailCap(CoverPhotos.DETAIL_CAP))
        assertEquals(8, CoverPhotos.DETAIL_CAP)
        assertEquals(12, CoverPhotos.MAX)
    }

    @Test
    fun adoptGalleryReplacesCardCoverWithDetailList() {
        val card = JSONObject()
            .put("image_url", "/uploads/a.jpg?w=1280&q=82")
            .put("images", JSONArray().put("/uploads/a.jpg?w=1280&q=82"))
        val detail = JSONObject()
            .put("ok", true)
            .put("images", JSONArray().put("/uploads/b.jpg").put("/uploads/a.jpg").put("/uploads/c.jpg"))
        CoverPhotos.adoptGallery(card, detail)
        val slots = CoverPhotos.slotsFrom(card)
        assertEquals(listOf("uploads/b.jpg", "uploads/a.jpg", "uploads/c.jpg"), slots.map { it.path })
        val shorter = JSONObject().put("images", JSONArray().put("/uploads/only.jpg"))
        CoverPhotos.adoptGallery(card, shorter)
        assertEquals(3, CoverPhotos.slotsFrom(card).size)
    }

    @Test
    fun savedEditPutsChosenCoverOnTheCard() {
        val card = JSONObject().put("code", "4").put("image_url", "/uploads/old.jpg").put("published_for_sale", true)
        val body = JSONObject()
            .put("code", "4")
            .put("images", JSONArray().put("uploads/cover.jpg").put("uploads/old.jpg"))
        val next = MyPropertyManage.applySavedEdit(card, body)
        assertEquals("/uploads/cover.jpg", next.getString("image_url"))
        assertEquals("uploads/cover.jpg", CoverPhotos.slotsFrom(next).first().path)
        assertTrue(next.getBoolean("published_for_sale"))
    }

    private fun pathsOf(slots: List<CoverPhotos.Slot>): List<String> {
        val arr = CoverPhotos.paths(slots)
        return List(arr.length()) { arr.getString(it) }
    }
}
