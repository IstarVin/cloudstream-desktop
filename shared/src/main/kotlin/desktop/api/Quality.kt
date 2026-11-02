package desktop.api

/**
 * Cloudstream's `Qualities`, copied so this module doesn't need the library. In enum order, so nearest-quality ties
 * resolve the same way (`web/src/lib/ranking.ts` keeps the same table).
 */
enum class Quality(val value: Int, val defaultPriority: Int) {
    Unknown(400, 4),
    P144(144, 0),
    P240(240, 2),
    P360(360, 3),
    P480(480, 4),
    P720(720, 5),
    P1080(1080, 6),
    P1440(1440, 7),
    P2160(2160, 8),
}
