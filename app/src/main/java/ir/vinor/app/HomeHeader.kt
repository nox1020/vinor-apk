package ir.vinor.app

/**
 * هدر خانه به سبک دیوار (هم‌تراز با search_feed.html وب):
 * برچسب‌ها بدون «▾» متنی — فلش، آیکون برداری کنار چیپ است؛ شهر داخل قرص جستجو با آیکون مکان.
 */
object HomeHeader {
    fun chipLabel(raw: String): String = raw.replace("▾", "").trim()

    fun cityLabel(city: String): String = city.trim().ifEmpty { SearchQuery.ALL_PROVINCES }

    fun showBell(signedIn: Boolean): Boolean = signedIn
}
