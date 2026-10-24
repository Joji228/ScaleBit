package app.morsel.data

/**
 * Regional food tuning. On (the default): scans, the AI coach and food search expect Greek foods,
 * dishes and brands, and Open Food Facts products sold in Greece come first. Turned off in
 * Settings, the AI gets no regional hint and search order is the database's own.
 *
 * [greekTuning] mirrors the saved setting; the app keeps it in step (see MorselApp).
 */
object AppRegion {
    /** Open Food Facts' tag for products sold in Greece. */
    const val FOOD_FACTS_COUNTRY = "en:greece"

    @Volatile
    var greekTuning: Boolean = true

    /** One line the AI prompts share; empty when the tuning is off. */
    val promptHint: String
        get() = if (greekTuning) {
            "The user lives in Greece: expect Greek foods, dishes, brands and portions unless they say otherwise " +
                "(for example Lacta is a Greek chocolate, Fage and Delta are Greek dairy brands, a souvlaki pita is a common meal)."
        } else {
            ""
        }
}
