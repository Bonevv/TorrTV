package app.torrtv.ui

/*
 * Чисти сметки за размерите на екраните. Нямат нищо общо с Compose и се проверяват с обикновени тестове.
 * Всички мерки са в една и съща единица (dp), а височината на плакат е 1,5 пъти широчината му.
 */

/**
 * Мащабът на интерфейса за екран с дадени размери в dp (виж Dims в Theme.kt): дизайнът е за 960x540dp и се
 * мащабира по по-тясната страна, между 0,8 и 2,4. Така кутията 960x540 винаги се събира в екрана.
 */
fun uiScale(widthDp: Int, heightDp: Int): Float =
    minOf(widthDp / 960f, heightDp / 540f).coerceIn(0.8f, 2.4f)

/**
 * Колони и широчина на плакат за дадена свободна широчина: плакатите са поне minW широки, броят им е най-малко 2,
 * а широчината се разпределя така, че редът е пълен и плакатите точно запълват мястото между отстъпите
 * (sidePad от двете страни и gap между плакатите).
 */
fun gridGeometry(width: Float, sidePad: Float, gap: Float, minW: Float): Pair<Int, Float> {
    val avail = (width - sidePad * 2).coerceAtLeast(0f)
    val cols = maxOf(((avail + gap) / (minW + gap)).toInt(), 2)
    return cols to (avail - gap * (cols - 1)) / cols
}

/**
 * Широчина на плакат в зара за един: пет заглавия в ред. w и h са свободното място; pad - отстъп от двете
 * страни на реда; gap - пролука между плакатите; textH - височина на всичко, което е под плаката и около него
 * (заглавие, година, въздух). Плакатът не е по-широк от мястото и не е по-висок от него, но не е под minW.
 */
fun diceCardWidth(w: Float, h: Float, pad: Float, gap: Float, textH: Float, minW: Float): Float =
    maxOf(minOf((w - pad * 2 - gap * 4) / 5, (h - textH) / 1.5f), minW)

/**
 * Широчина на плакат в зара за двама: две страни по две заглавия и монета между тях.
 * pad - отстъп от двете страни; gap - пролука между страните и монетата; inner - пролука между двете заглавия
 * в една страна; coin - широчина на монетата; head - височина над плаката (надпис на страната), textH - под него.
 */
fun pairCardWidth(
    w: Float, h: Float, pad: Float, gap: Float, inner: Float, coin: Float, head: Float, textH: Float, minW: Float
): Float = maxOf(minOf((w - pad * 2 - gap * 2 - coin - inner * 2) / 4, (h - head - textH) / 1.5f), minW)
