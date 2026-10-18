package app.torrtv.web

import java.util.concurrent.ConcurrentHashMap

/**
 * Блокиране на рекламни и проследяващи домейни в уеб източниците. Списъкът е взет от проекта tvsites.
 * Блокира се домейнът и всичките му поддомейни; решението се помни, защото една страница праща стотици заявки.
 */
object AdBlock {
    private val blocked: Set<String> = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com", "adsystem.com",
        "amazon-adsystem.com", "trafficstars.com", "tsyndicate.com", "popads.net", "popcash.net",
        "propellerads.com", "propellerclick.com", "exoclick.com", "exosrv.com", "juicyads.com",
        "adsterra.com", "highperformanceformat.com", "onclickads.net", "ad-maven.com", "admaven.com",
        "clickadu.com", "hilltopads.net", "trafficjunky.net", "adnxs.com", "taboola.com", "outbrain.com",
        "criteo.com", "pubmatic.com", "rubiconproject.com", "openx.net", "adform.net", "smartadserver.com",
        "mgid.com", "revcontent.com", "adcash.com", "bidvertiser.com", "a-ads.com", "monetag.com",
        "richpush.co", "pushame.com", "realsrv.com", "an.yandex.ru", "mc.yandex.ru", "ads.yahoo.com",
        "adskeeper.com", "adsco.re", "adapex.io", "adsrvr.org", "casalemedia.com", "bidswitch.net",
        "sharethrough.com", "sovrn.com", "33across.com", "id5-sync.com", "contextweb.com", "teads.tv",
        "onetag-sys.com", "profitablecpmrate.com", "profitablegatecpm.com", "effectivegatecpm.com",
        "revenuecpmgate.com", "clickadilla.com", "mondiad.com", "acscdn.com", "exdynsrv.com", "magsrv.com",
        "plugrush.com", "tubecorporate.com", "criteo.net", "moatads.com", "doubleverify.com",
        "adsafeprotected.com", "serving-sys.com", "advertising.com", "yieldmo.com", "lijit.com",
        "indexww.com", "3lift.com", "triplelift.com", "gumgum.com", "media.net", "everesttech.net",
        "demdex.net", "krxd.net", "bluekai.com", "rlcdn.com", "tapad.com", "agkn.com", "mathtag.com",
        "bidr.io", "2mdn.net", "scorecardresearch.com", "quantserve.com", "quantcount.com", "hotjar.com",
        "clarity.ms", "histats.com", "statcounter.com", "gemius.pl", "gemius.com", "adocean.pl",
        "adocean-global.com", "yadro.ru", "liveinternet.ru", "mixpanel.com", "facebook.net", "addthis.com",
        "sharethis.com"
    )

    private val cache = ConcurrentHashMap<String, Boolean>()

    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        cache[host]?.let { return it }
        var h = host.lowercase()
        var res = false
        while (true) {
            if (h in blocked) {
                res = true
                break
            }
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) break // не проверяваме само края (.com)
            h = h.substring(dot + 1)
        }
        if (cache.size > 3000) cache.clear()
        cache[host] = res
        return res
    }

    /** Основният домейн (пример.com от a.b.пример.com). Достатъчно точно, за да познаем „същия сайт“. */
    fun baseOf(host: String?): String {
        if (host.isNullOrEmpty()) return ""
        val p = host.lowercase().removePrefix("www.").split('.')
        if (p.size <= 2) return p.joinToString(".")
        val twoLevel = p[p.size - 1].length == 2 && p[p.size - 2] in setOf("co", "com", "org", "net", "gov", "ac", "edu")
        return p.takeLast(if (twoLevel) 3 else 2).joinToString(".")
    }
}
