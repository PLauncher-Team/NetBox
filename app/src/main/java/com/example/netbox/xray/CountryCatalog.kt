package com.example.netbox.xray

import android.content.Context
import org.json.JSONArray
import java.util.Locale

data class CountryInfo(
    val code: String,
    val alpha3: String,
    val englishName: String,
    val russianName: String
) {
    val flag: String
        get() = code.uppercase(Locale.ROOT)
            .map { String(Character.toChars(it.code + 127397)) }
            .joinToString("")

    val displayName: String
        get() = russianName.ifBlank { englishName }

    companion object {
        const val AUTO_CODE = "auto"
    }
}

/**
 * Country names are bundled from the CC BY-SA 4.0 dataset documented in
 * assets/COUNTRY_DATA_ATTRIBUTION.txt. Nothing is downloaded at runtime.
 */
object CountryCatalog {
    @Volatile
    private var cachedCountries: List<CountryInfo>? = null

    fun load(context: Context): List<CountryInfo> {
        cachedCountries?.let { return it }
        return synchronized(this) {
            cachedCountries?.let { return@synchronized it }
            val json = context.assets.open("countries.json").bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            val loaded = buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val code = item.optString("code").uppercase(Locale.ROOT)
                    val alpha3 = item.optString("alpha3").uppercase(Locale.ROOT)
                    val englishName = item.optString("en").trim()
                    val russianName = item.optString("ru").trim()
                    if (code.length == 2 && englishName.isNotEmpty() && russianName.isNotEmpty()) {
                        add(CountryInfo(code, alpha3, englishName, russianName))
                    }
                }
            }.sortedBy { it.russianName.lowercase(Locale.ROOT) }
            cachedCountries = loaded
            loaded
        }
    }

    /**
     * Matches a country flag emoji first, then full English/Russian country
     * names at word boundaries. ISO codes are accepted only as standalone
     * uppercase labels to avoid common false positives.
     */
    fun detectCountry(remark: String, countries: List<CountryInfo>): CountryInfo? {
        // Check the original string before normalization, which removes emoji.
        val flagMatch = countries.firstOrNull { country ->
            remark.contains(country.flag)
        }
        if (flagMatch != null) return flagMatch

        val normalizedRemark = normalize(remark)
        if (normalizedRemark.isBlank()) return null

        val paddedRemark = " $normalizedRemark "
        val nameMatch = countries.asSequence()
            .flatMap { country ->
                sequenceOf(country.englishName to country, country.russianName to country)
            }
            .map { (alias, country) -> normalize(alias) to country }
            .filter { (alias, _) ->
                alias.length >= 3 && paddedRemark.contains(" $alias ")
            }
            .maxByOrNull { (alias, _) -> alias.length }
            ?.second

        if (nameMatch != null) return nameMatch

        return countries.firstOrNull { country ->
            hasStandaloneUppercaseCode(remark, country.code) ||
                (country.alpha3.isNotBlank() && hasStandaloneUppercaseCode(remark, country.alpha3))
        }
    }

    fun filterByCountry(
        configs: List<VlessConfig>,
        countryCode: String,
        countries: List<CountryInfo>
    ): List<VlessConfig> {
        if (countryCode == CountryInfo.AUTO_CODE) return configs
        return configs.filter { detectCountry(it.remark, countries)?.code == countryCode }
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun hasStandaloneUppercaseCode(value: String, code: String): Boolean {
        if (code.isBlank()) return false
        val pattern = Regex("(?<![A-Za-z0-9])${Regex.escape(code)}(?![A-Za-z0-9])")
        return pattern.containsMatchIn(value)
    }
}
