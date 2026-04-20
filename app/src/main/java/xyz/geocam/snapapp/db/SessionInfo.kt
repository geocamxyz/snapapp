package xyz.geocam.snapapp.db

data class SessionInfo(
    val shotCount: Int,
    val firstLat: Double?,
    val firstLon: Double?,
    val shotIds: List<Long>
)
