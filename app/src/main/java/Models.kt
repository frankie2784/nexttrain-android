package com.nexttrain.data

import java.time.LocalTime
import java.time.DayOfWeek

// ── App domain models ──────────────────────────────────────────────────────

/**
 * A single processed departure shown in the widget.
 */
data class Departure(
    val scheduledTime: String,      // "HH:mm" in local time
    val estimatedTime: String?,     // "HH:mm" if real-time differs from scheduled
    val delayMinutes: Int,          // positive = late, 0 = on time, negative = early
    val platformNumber: String?,
    val minutesUntilDeparture: Long,
    val departureUnixTs: Long? = null,           // epoch seconds; enables client-side recalc
    val destinationScheduledTime: String? = null, // "HH:mm" scheduled arrival at destination
    val destinationEstimatedTime: String? = null, // "HH:mm" real-time arrival at destination
) {
    val isDelayed: Boolean get() = delayMinutes >= 1
    val isEarly: Boolean get() = delayMinutes <= -1
    val expectedTime: String get() = estimatedTime ?: scheduledTime
    val hasRealtimeTimeChange: Boolean get() = expectedTime != scheduledTime
    val displayTime: String get() = expectedTime
    val destinationDisplayTime: String? get() = destinationEstimatedTime ?: destinationScheduledTime
}

/**
 * Recomputes [Departure.minutesUntilDeparture] against the current clock when
 * [Departure.departureUnixTs] is available, rather than trusting the value
 * baked in at fetch time. Cached departures (disk cache, or served while the
 * server is unreachable) can sit unread for many minutes — without this, the
 * countdown freezes at whatever it read when it was written, which looks
 * exactly like stale/stuck data to the user even though the entry is
 * otherwise correctly marked as offline.
 */
fun Departure.withCurrentCountdown(nowMs: Long = System.currentTimeMillis()): Departure {
    val ts = departureUnixTs ?: return this
    return copy(minutesUntilDeparture = (ts - nowMs / 1000L) / 60L)
}

/**
 * True once this departure is more than [graceSeconds] past due. A live,
 * freshly-fetched departure legitimately reading a minute or so overdue is
 * normal (real-time delay data, fetch/render latency) and not something to
 * hide — the grace window is generous specifically so genuinely current data
 * is never second-guessed. What it still catches is staleness: cached data
 * that's been sitting unrefreshed for a while (see DeparturesRepository's
 * offline fallback) eventually drifts past even this window and gets dropped,
 * rather than a departed train showing "Now" indefinitely because nothing
 * ever re-checks it.
 */
fun Departure.hasDeparted(nowMs: Long = System.currentTimeMillis(), graceSeconds: Long = 75): Boolean {
    val ts = departureUnixTs ?: return minutesUntilDeparture < -1
    return nowMs / 1000L - ts > graceSeconds
}

/** Drops departures more than a few seconds past due — see [Departure.hasDeparted]. */
fun List<Departure>.dropDeparted(nowMs: Long = System.currentTimeMillis()): List<Departure> =
    filterNot { it.hasDeparted(nowMs) }

// ── Delay history (idle-state sparkline) ──────────────────────────────────

data class DelayPoint(
    val secondsAgo: Int,
    val totalDelayMinutes: Float
)

// Raw Gson-deserialised DTOs matching /delay_history JSON keys
data class DelayHistoryResponse(
    val window_minutes: Int,
    val points: List<DelayHistoryPoint>
)

data class DelayHistoryPoint(
    val seconds_ago: Int,
    val total_delay_minutes: Float
)

// ── Regions ──────────────────────────────────────────────────────────────

/**
 * A supported GTFS region/agency. [apiPath] is the server's URL prefix, e.g. "/vic/...".
 * [hasRealtime] mirrors the server's `rt_auth_mode == "disabled"` (see regions.py) — regions
 * without a GTFS-RT feed wired up never have delay data, so the client shouldn't treat an
 * empty /delay_history response from them as a network failure.
 */
enum class Region(val apiPath: String, val displayName: String, val hasRealtime: Boolean = true) {
    VIC("vic", "Victoria"),
    SA("sa", "South Australia"),
    NSW("nsw", "New South Wales"),
    QLD("qld", "Queensland"),
    WA("wa", "Western Australia", hasRealtime = false),
}

// ── Melbourne Metro stations ───────────────────────────────────────────────

data class Station(val name: String, val stopId: Int, val region: Region, val sequence: Int? = null)

/** A GTFS route treated as a train line, for the route-editor's line picker. */
data class Line(val lineId: String, val name: String, val color: String?)

/**
 * Offline fallback list of Melbourne Metro train stations with PTV stop IDs.
 * Only used when no server is configured or the live catalog can't be
 * reached — [com.nexttrain.prefs.WidgetPrefs] caches the server's live
 * `/stations` response and that cache is always preferred when present,
 * since PTV's public stop IDs and the station list itself (new stations,
 * renames, closures) drift over time and this list is not auto-updated.
 * Regenerated from the Victoria GTFS static feed on 2026-08-06.
 */
object MelbourneStations {

    private fun s(name: String, stopId: Int) = Station(name, stopId, Region.VIC)

    val ALL: List<Station> = listOf(
        // Hurstbridge
        s("Jolimont-MCG", 1104),
        s("West Richmond", 1207),
        s("North Richmond", 1145),
        s("Collingwood", 1043),
        s("Victoria Park", 1201),
        s("Clifton Hill", 1041),
        s("Westgarth", 1209),
        s("Dennis", 1053),
        s("Fairfield", 1065),
        s("Alphington", 1004),
        s("Darebin", 1050),
        s("Ivanhoe", 1101),
        s("Eaglemont", 1056),
        s("Heidelberg", 1093),
        s("Rosanna", 1168),
        s("Macleod", 1117),
        s("Watsonia", 1203),
        s("Greensborough", 1084),
        s("Montmorency", 1130),
        s("Eltham", 1062),
        s("Diamond Creek", 1054),
        s("Wattle Glen", 1204),
        s("Hurstbridge", 1100),

        // Mernda
        s("Rushall", 1170),
        s("Merri", 1125),
        s("Northcote", 1147),
        s("Croxton", 1047),
        s("Thornbury", 1193),
        s("Bell", 1019),
        s("Preston", 1159),
        s("Regent", 1160),
        s("Reservoir", 1161),
        s("Ruthven", 1171),
        s("Keon Park", 1109),
        s("Thomastown", 1192),
        s("Lalor", 1112),
        s("Epping", 1063),
        s("South Morang", 1224),
        s("Middle Gorge", 1226),
        s("Hawkstowe", 1227),
        s("Mernda", 1228),

        // Belgrave / Lilydale
        s("Flinders Street", 1071),
        s("Southern Cross", 1181),
        s("Flagstaff", 1068),
        s("Melbourne Central", 1120),
        s("Parliament", 1155),
        s("Richmond", 1162),
        s("Burnley", 1030),
        s("Hawthorn", 1090),
        s("Glenferrie", 1080),
        s("Auburn", 1012),
        s("Camberwell", 1032),
        s("East Camberwell", 1057),
        s("Canterbury", 1033),
        s("Chatham", 1037),
        s("Union", 1229),
        s("Box Hill", 1026),
        s("Laburnum", 1111),
        s("Blackburn", 1023),
        s("Nunawading", 1148),
        s("Mitcham", 1128),
        s("Heatherdale", 1091),
        s("Ringwood", 1163),
        s("Ringwood East", 1164),
        s("Croydon", 1048),
        s("Mooroolbark", 1133),
        s("Lilydale", 1115),
        s("Belgrave", 1018),
        s("Tecoma", 1191),
        s("Upwey", 1200),
        s("Upper Ferntree Gully", 1199),
        s("Ferntree Gully", 1067),
        s("Boronia", 1025),
        s("Bayswater", 1016),
        s("Heathmont", 1092),
        s("East Richmond", 1059),

        // Alamein
        s("Riversdale", 1166),
        s("Willison", 1213),
        s("Hartwell", 1087),
        s("Burwood", 1031),
        s("Ashburton", 1010),
        s("Alamein", 1002),

        // Glen Waverley
        s("Heyington", 1094),
        s("Kooyong", 1110),
        s("Tooronga", 1195),
        s("Gardiner", 1075),
        s("Glen Iris", 1077),
        s("Darling", 1051),
        s("East Malvern", 1058),
        s("Holmesglen", 1096),
        s("Jordanville", 1105),
        s("Mount Waverley", 1137),
        s("Syndal", 1190),
        s("Glen Waverley", 1078),

        // Frankston
        s("Frankston", 1073),
        s("Kananook", 1106),
        s("Seaford", 1174),
        s("Carrum", 1035),
        s("Bonbeach", 1024),
        s("Chelsea", 1038),
        s("Edithvale", 1060),
        s("Aspendale", 1011),
        s("Mordialloc", 1134),
        s("Parkdale", 1154),
        s("Mentone", 1122),
        s("Cheltenham", 1039),
        s("Southland", 1001),
        s("Highett", 1095),
        s("Moorabbin", 1132),
        s("Patterson", 1157),
        s("Bentleigh", 1020),
        s("McKinnon", 1119),
        s("Ormond", 1152),
        s("Glen Huntly", 1081),
        s("Armadale", 1008),
        s("Toorak", 1194),
        s("Hawksburn", 1089),
        s("South Yarra", 1180),

        // Sandringham
        s("Sandringham", 1173),
        s("Hampton", 1086),
        s("Brighton Beach", 1027),
        s("Middle Brighton", 1126),
        s("North Brighton", 1143),
        s("Gardenvale", 1074),
        s("Elsternwick", 1061),
        s("Ripponlea", 1165),
        s("Balaclava", 1013),
        s("Windsor", 1214),
        s("Prahran", 1158),

        // Cranbourne
        s("Town Hall", 1235),
        s("Anzac", 1236),
        s("Malvern", 1118),
        s("Caulfield", 1036),
        s("Carnegie", 1034),
        s("Murrumbeena", 1138),
        s("Hughesdale", 1098),
        s("Oakleigh", 1150),
        s("Huntingdale", 1099),
        s("Clayton", 1040),
        s("Westall", 1208),
        s("Springvale", 1183),
        s("Sandown Park", 1172),
        s("Noble Park", 1142),
        s("Yarraman", 1215),
        s("Dandenong", 1049),
        s("Lynbrook", 1222),
        s("Merinda Park", 1123),
        s("Cranbourne", 1045),

        // Pakenham
        s("Hallam", 1085),
        s("Narre Warren", 1139),
        s("Berwick", 1021),
        s("Beaconsfield", 1017),
        s("Officer", 1151),
        s("Cardinia Road", 1223),
        s("Pakenham", 1153),
        s("East Pakenham", 1230),

        // Stony Point
        s("Stony Point", 1185),
        s("Crib Point", 1046),
        s("Morradoo", 1136),
        s("Bittern", 1022),
        s("Hastings", 1088),
        s("Tyabb", 1197),
        s("Somerville", 1178),
        s("Baxter", 1015),
        s("Leawarra", 1114),

        // Williamstown
        s("Williamstown", 1211),
        s("Williamstown Beach", 1212),
        s("North Williamstown", 1146),
        s("Newport", 1141),
        s("Spotswood", 1182),
        s("Yarraville", 1216),
        s("Seddon", 1176),
        s("Footscray", 1072),
        s("South Kensington", 1179),
        s("North Melbourne", 1144),

        // Werribee
        s("Werribee", 1205),
        s("Hoppers Crossing", 1097),
        s("Williams Landing", 1225),
        s("Aircraft", 1220),
        s("Laverton", 1113),
        s("Westona", 1210),
        s("Altona", 1005),
        s("Seaholme", 1175),

        // Sunbury
        s("State Library", 1234),
        s("Parkville", 1233),
        s("Arden", 1232),
        s("Middle Footscray", 1127),
        s("West Footscray", 1206),
        s("Tottenham", 1196),
        s("Sunshine", 1218),
        s("Albion", 1003),
        s("Ginifer", 1076),
        s("St Albans", 1184),
        s("Keilor Plains", 1107),
        s("Watergardens", 1202),
        s("Diggers Rest", 1055),
        s("Sunbury", 1187),

        // Craigieburn
        s("Craigieburn", 1044),
        s("Roxburgh Park", 1219),
        s("Coolaroo", 1221),
        s("Broadmeadows", 1028),
        s("Jacana", 1102),
        s("Glenroy", 1082),
        s("Oak Park", 1149),
        s("Pascoe Vale", 1156),
        s("Strathmore", 1186),
        s("Glenbervie", 1079),
        s("Essendon", 1064),
        s("Moonee Ponds", 1131),
        s("Ascot Vale", 1009),
        s("Newmarket", 1140),
        s("Kensington", 1108),

        // Upfield
        s("Upfield", 1198),
        s("Gowrie", 1083),
        s("Fawkner", 1066),
        s("Merlynston", 1124),
        s("Batman", 1014),
        s("Coburg", 1042),
        s("Moreland", 1135),
        s("Anstey", 1006),
        s("Brunswick", 1029),
        s("Jewell", 1103),
        s("Royal Park", 1169),
        s("Flemington Bridge", 1069),
        s("Macaulay", 1116),

        // Special event only
        s("Flemington Racecourse", 1070),
    ).distinctBy { it.stopId }.sortedBy { it.name }
}

// ── Widget OD Pair configuration ───────────────────────────────────────────

/**
 * One configured origin→destination pair with an active time window.
 */
data class OdPair(
    val id: String,             // UUID
    val label: String,          // e.g. "Morning commute"
    val originStopId: Int,
    val originName: String,
    val destinationStopId: Int,
    val destinationName: String,
    val activeFrom: LocalTime,  // start of active window (inclusive)
    val activeTo: LocalTime,    // end of active window (inclusive)
    val activeDays: Set<Int> = (1..7).toSet(), // java.time.DayOfWeek values (1=Mon..7=Sun)
    val directionId: Int = -1,   // -1 = auto-detect from destination
    val notificationsEnabled: Boolean = true, // per-route notification toggle
    val includeOnWidget: Boolean = true, // whether this route is cycled through on the home screen widget
    val lineId: String? = null, // GTFS route_id the origin/destination pickers were filtered to; null = "All Lines"
    val region: Region = Region.VIC // which GTFS region this route's stop ids belong to
) {
    /**
     * True when the current moment falls inside this pair's active window.
     *
     * Handles windows that cross midnight (e.g. 22:00–01:30 for a late
     * shift) — the route editor's time pickers place no constraint on
     * activeFrom being before activeTo, so a same-day-only check would make
     * an overnight window impossible to ever satisfy. For an overnight
     * window, [activeDays] is anchored to the day the window *starts*: a
     * pair active Fri 22:00–01:30 is "on" from Friday 22:00 through
     * Saturday 01:30, even though Saturday itself isn't separately selected.
     */
    fun isActiveNow(): Boolean {
        val now = LocalTime.now()
        val today = DayOfWeek.from(java.time.LocalDate.now()).value
        if (!activeFrom.isAfter(activeTo)) {
            return today in activeDays && !now.isBefore(activeFrom) && !now.isAfter(activeTo)
        }
        val yesterday = if (today == 1) 7 else today - 1
        return (today in activeDays && !now.isBefore(activeFrom)) ||
            (yesterday in activeDays && !now.isAfter(activeTo))
    }
}
