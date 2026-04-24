package xyz.geocam.snapapp.recognition

data class MatchResult(
    val id: Long,
    val score: Float,   // inner product ≈ cosine similarity; higher = better
    val lat: Double,
    val lon: Double,
    val label: String?
)
