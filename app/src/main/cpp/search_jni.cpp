#include <jni.h>
#include <cstring>
#include <cstdint>
#include <cstdlib>
#include <algorithm>
#include <vector>
#include <arm_neon.h>

// Flat index: stores L2-normalised float32 vectors + int64 IDs.
// Uses inner product (== cosine similarity for normalised vectors).
struct FlatIndex {
    float*   vectors;  // n * dim floats
    int64_t* ids;
    int      n;
    int      dim;
};

// Vectorised inner product using ARM NEON.
static inline float dot_neon(const float* __restrict__ a,
                              const float* __restrict__ b,
                              int dim) {
    float32x4_t acc = vdupq_n_f32(0.f);
    int i = 0;
    for (; i <= dim - 4; i += 4) {
        acc = vmlaq_f32(acc, vld1q_f32(a + i), vld1q_f32(b + i));
    }
    float s = vaddvq_f32(acc);
    for (; i < dim; ++i) s += a[i] * b[i];
    return s;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_xyz_geocam_snapapp_recognition_SearchIndex_nativeLoad(
        JNIEnv* env, jclass,
        jfloatArray jvectors, jlongArray jids, jint dim) {

    jsize n = env->GetArrayLength(jids);
    auto* idx = new FlatIndex();
    idx->n   = (int)n;
    idx->dim = (int)dim;

    idx->vectors = new float[n * dim];
    jfloat* v = env->GetFloatArrayElements(jvectors, nullptr);
    memcpy(idx->vectors, v, (size_t)(n * dim) * sizeof(float));
    env->ReleaseFloatArrayElements(jvectors, v, JNI_ABORT);

    idx->ids = new int64_t[n];
    jlong* ids = env->GetLongArrayElements(jids, nullptr);
    memcpy(idx->ids, ids, (size_t)n * sizeof(int64_t));
    env->ReleaseLongArrayElements(jids, ids, JNI_ABORT);

    return (jlong)(uintptr_t)idx;
}

JNIEXPORT void JNICALL
Java_xyz_geocam_snapapp_recognition_SearchIndex_nativeRelease(
        JNIEnv*, jclass, jlong handle) {
    auto* idx = (FlatIndex*)(uintptr_t)handle;
    delete[] idx->vectors;
    delete[] idx->ids;
    delete idx;
}

// Returns float array of [id0_as_float, score0, id1_as_float, score1, ...] for top-k.
JNIEXPORT jfloatArray JNICALL
Java_xyz_geocam_snapapp_recognition_SearchIndex_nativeSearch(
        JNIEnv* env, jclass, jlong handle, jfloatArray jQuery, jint topK) {

    auto* idx = (FlatIndex*)(uintptr_t)handle;
    jfloat* q = env->GetFloatArrayElements(jQuery, nullptr);

    int k = std::min(topK, idx->n);

    // Score every vector.
    std::vector<std::pair<float, int>> scores(idx->n);
    for (int i = 0; i < idx->n; ++i) {
        scores[i] = { dot_neon(q, idx->vectors + (size_t)i * idx->dim, idx->dim), i };
    }
    env->ReleaseFloatArrayElements(jQuery, q, JNI_ABORT);

    std::partial_sort(scores.begin(), scores.begin() + k, scores.end(),
        [](const auto& a, const auto& b){ return a.first > b.first; });

    // Pack result as [id_float, score, id_float, score, ...]
    jfloatArray result = env->NewFloatArray(k * 2);
    std::vector<float> out(k * 2);
    for (int i = 0; i < k; ++i) {
        out[i * 2]     = (float)idx->ids[scores[i].second];
        out[i * 2 + 1] = scores[i].first;
    }
    env->SetFloatArrayRegion(result, 0, k * 2, out.data());
    return result;
}

} // extern "C"
