package xyz.geocam.snapapp.recognition

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages which SearchIndex shard is active based on the phone's GPS position.
 *
 * Shard directory layout:
 *   <filesDir>/recognition/
 *     shards.json          – array of shard descriptors
 *     <shard_id>/
 *       vectors.bin
 *       meta.db
 *       extent.json
 *
 * shards.json entry:
 *   {"id":"name","dim":1024,"polygon":[[lat,lon],...]}
 *
 * Polygon winding order doesn't matter; uses ray-cast point-in-polygon.
 */
class ShardManager(context: Context) : AutoCloseable {

    data class ShardDesc(
        val id: String,
        val dir: File,
        val polygon: List<Pair<Double, Double>>  // (lat, lon)
    )

    private val root = File(context.filesDir, "recognition")
    private val shards: List<ShardDesc>
    private var activeIndex: SearchIndex? = null
    private var activeShardId: String? = null

    init {
        val catalogFile = File(root, "shards.json")
        shards = if (catalogFile.exists()) {
            val arr = JSONArray(catalogFile.readText())
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                val poly = parsePolygon(obj.getJSONArray("polygon"))
                ShardDesc(obj.getString("id"), File(root, obj.getString("id")), poly)
            }
        } else emptyList()
    }

    /** Call whenever GPS updates. Loads/unloads shards as needed. Returns true if shard changed. */
    fun updateLocation(lat: Double, lon: Double): Boolean {
        val matching = shards.firstOrNull { pointInPolygon(lat, lon, it.polygon) }
        if (matching?.id == activeShardId) return false

        activeIndex?.close()
        activeIndex = null
        activeShardId = null

        if (matching != null && matching.dir.exists()) {
            activeIndex = try { SearchIndex(matching.dir) } catch (e: Exception) { null }
            activeShardId = matching.id
        }
        return true
    }

    fun search(query: FloatArray): MatchResult? = activeIndex?.search(query)

    val activeShardName: String? get() = activeShardId

    val shardCount: Int get() = shards.size

    override fun close() {
        activeIndex?.close()
        activeIndex = null
    }

    private fun parsePolygon(arr: JSONArray): List<Pair<Double, Double>> =
        List(arr.length()) { i ->
            val pt = arr.getJSONArray(i)
            Pair(pt.getDouble(0), pt.getDouble(1))
        }

    // Ray-casting algorithm; polygon coords are (lat, lon).
    private fun pointInPolygon(lat: Double, lon: Double, poly: List<Pair<Double, Double>>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (yi, xi) = poly[i]
            val (yj, xj) = poly[j]
            if ((yi > lat) != (yj > lat) &&
                lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
