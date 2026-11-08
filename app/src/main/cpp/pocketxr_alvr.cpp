// PC VR in the Space: Pocket XR's bridge to ALVR's client library (alvr_client_core, MIT licence).
//
// ALVR connects to the PC streamer and decodes its video. Each decoded frame (both eyes side by side, foveated encoding
// off) is a hardware buffer that Pocket XR shows directly in its own OpenGL context, as the Space shows its windows:
// ALVR's own drawing (alvr_*_opengl) makes a context of its own current on the caller's thread, so it isn't used.
// [nativeAcquireFrame] runs on the caller's GL thread; the rest may run on any thread.

#include <jni.h>
#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#include "alvr/alvr_client_core.h"

#define TAG "PocketXrAlvr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {
JavaVM *g_vm = nullptr;
jobject g_context = nullptr;
bool g_initialized = false;

// One swapchain image per eye: ALVR draws into these.
uint32_t g_left[1] = {0};
uint32_t g_right[1] = {0};
const uint32_t *g_swapchains[2] = {g_left, g_right};

// The last StreamingStarted's foveation, for alvr_start_stream_opengl.
AlvrFoveatedEncodingParams g_foveation{};
bool g_foveation_on = false;

uint64_t g_head_id = 0;

AlvrFov fov_of(const float *f) { return AlvrFov{f[0], f[1], f[2], f[3]}; }

AlvrQuat quat_of(const float *q) { return AlvrQuat{q[0], q[1], q[2], q[3]}; }

// The eye's pose in the head's frame turned into the world by the head's orientation.
AlvrPose eye_pose(const AlvrQuat &head, float eye_x) {
    // Rotate (eye_x, 0, 0) by the head quaternion.
    const float x = head.x, y = head.y, z = head.z, w = head.w;
    const float vx = eye_x;
    // v' = v + 2w(q x v) + 2 q x (q x v), with v = (vx, 0, 0).
    const float cx = 0.0f, cy = z * vx, cz = -y * vx;  // q x v
    const float dx = y * cz - z * cy, dy = z * cx - x * cz, dz = x * cy - y * cx;  // q x (q x v)
    AlvrPose p{};
    p.orientation = head;
    p.position[0] = vx + 2.0f * (w * cx + dx);
    p.position[1] = 2.0f * (w * cy + dy);
    p.position[2] = 2.0f * (w * cz + dz);
    return p;
}
}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

/** Starts ALVR: it looks for the PC streamer on the network and keeps the connection up. */
JNIEXPORT void JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativeInit(
    JNIEnv *env, jclass, jobject context, jint view_width, jint view_height, jfloat refresh_hz) {
    if (g_initialized) return;
    g_context = env->NewGlobalRef(context);
    alvr_initialize_android_context(g_vm, g_context);
    alvr_initialize_logging();
    const float rates[1] = {refresh_hz};
    AlvrClientCapabilities caps{};
    caps.default_view_width = static_cast<uint32_t>(view_width);
    caps.default_view_height = static_cast<uint32_t>(view_height);
    caps.max_view_width = static_cast<uint32_t>(view_width);
    caps.max_view_height = static_cast<uint32_t>(view_height);
    caps.refresh_rates = rates;
    caps.refresh_rates_count = 1;
    caps.foveated_encoding = false;  // a plain side by side frame Pocket XR can sample directly
    caps.encoder_high_profile = true;
    caps.encoder_10_bits = false;
    caps.encoder_av1 = false;
    caps.prefer_10bit = false;
    caps.preferred_encoding_gamma = 1.0f;
    caps.prefer_hdr = false;
    alvr_initialize(caps);
    alvr_resume();
    g_head_id = alvr_path_string_to_id("/user/head");
    g_initialized = true;
    char protocol[64] = {0};
    alvr_protocol_id(protocol);
    char host[128] = {0};
    alvr_hostname(host);
    LOGI("ALVR started: %dx%d per eye at %.1f Hz, protocol %s, this phone shows as %s", view_width, view_height,
         refresh_hz, protocol, host);
}

JNIEXPORT void JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativeDestroy(JNIEnv *env, jclass) {
    if (!g_initialized) return;
    alvr_pause();
    alvr_destroy();
    g_initialized = false;
    if (g_context != nullptr) {
        env->DeleteGlobalRef(g_context);
        g_context = nullptr;
    }
    LOGI("ALVR stopped");
}

/** GL thread: ALVR draws into these two textures (one per eye) at [width] x [height]. */
JNIEXPORT void JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativeGlResume(
    JNIEnv *, jclass, jint width, jint height, jint left_texture, jint right_texture) {
    g_left[0] = static_cast<uint32_t>(left_texture);
    g_right[0] = static_cast<uint32_t>(right_texture);
    alvr_initialize_opengl();
    alvr_resume_opengl(static_cast<uint32_t>(width), static_cast<uint32_t>(height), g_swapchains, 1);
}

JNIEXPORT void JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativeGlPause(JNIEnv *, jclass) {
    alvr_pause_opengl();
    alvr_destroy_opengl();
}

/**
 * The next event, or -1 with none. [out] gets: StreamingStarted (1) view width, height, refresh hint, foveation on;
 * Haptics (3) device id (low 32 bits), duration s, frequency, amplitude; DecoderConfig (4) the codec.
 */
JNIEXPORT jint JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativePollEvent(JNIEnv *env, jclass, jfloatArray out) {
    AlvrEvent e{};
    if (!alvr_poll_event(&e)) return -1;
    float v[4] = {0, 0, 0, 0};
    switch (e.tag) {
        case ALVR_EVENT_STREAMING_STARTED:
            v[0] = static_cast<float>(e.STREAMING_STARTED.view_width);
            v[1] = static_cast<float>(e.STREAMING_STARTED.view_height);
            v[2] = e.STREAMING_STARTED.refresh_rate_hint;
            v[3] = e.STREAMING_STARTED.enable_foveated_encoding ? 1.0f : 0.0f;
            g_foveation = e.STREAMING_STARTED.foveated_encoding;
            g_foveation_on = e.STREAMING_STARTED.enable_foveated_encoding;
            break;
        case ALVR_EVENT_HAPTICS:
            v[0] = static_cast<float>(e.HAPTICS.device_id & 0xffffffffu);
            v[1] = e.HAPTICS.duration_s;
            v[2] = e.HAPTICS.frequency;
            v[3] = e.HAPTICS.amplitude;
            break;
        case ALVR_EVENT_DECODER_CONFIG:
            v[0] = static_cast<float>(e.DECODER_CONFIG.codec);
            break;
        default:
            break;
    }
    env->SetFloatArrayRegion(out, 0, 4, v);
    return static_cast<jint>(e.tag);
}

/** The HUD text ALVR shows in its lobby ("Searching for streamer", the hostname, errors). */
JNIEXPORT jstring JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativeHudMessage(JNIEnv *env, jclass) {
    std::vector<char> buf(alvr_hud_message(nullptr) + 1, 0);
    alvr_hud_message(buf.data());
    return env->NewStringUTF(buf.data());
}

/** After DecoderConfig: the hardware decoder for [codec], with ALVR's buffering of [max_buffering] frames. */
JNIEXPORT void JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativeCreateDecoder(
    JNIEnv *, jclass, jint codec, jfloat max_buffering) {
    const uint64_t size = alvr_get_decoder_config(nullptr);
    std::vector<char> config(size);
    if (size > 0) alvr_get_decoder_config(config.data());
    AlvrMediacodecOption options[2];
    options[0].key = "operating-rate";
    options[0].ty = ALVR_MEDIACODEC_PROP_TYPE_INT32;
    options[0].value.int32 = 120;
    options[1].key = "priority";
    options[1].ty = ALVR_MEDIACODEC_PROP_TYPE_INT32;
    options[1].value.int32 = 0;
    AlvrDecoderConfig dc{};
    dc.codec = static_cast<AlvrCodecType>(codec);
    dc.force_software_decoder = false;
    dc.max_buffering_frames = max_buffering;
    dc.buffering_history_weight = 0.9f;
    dc.options = options;
    dc.options_count = 2;
    dc.config_buffer = reinterpret_cast<const uint8_t *>(config.data());
    dc.config_buffer_size = size;
    alvr_create_decoder(dc);
    LOGI("decoder: codec %d, %llu config bytes, buffering %.1f frames", codec, static_cast<unsigned long long>(size),
         max_buffering);
}

JNIEXPORT void JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativeDestroyDecoder(JNIEnv *, jclass) {
    alvr_destroy_decoder();
}

/** GL thread, after StreamingStarted: ALVR's stream drawing at the negotiated [width] x [height]. */
JNIEXPORT void JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativeStartStream(
    JNIEnv *, jclass, jint width, jint height) {
    AlvrStreamConfig sc{};
    sc.view_resolution_width = static_cast<uint32_t>(width);
    sc.view_resolution_height = static_cast<uint32_t>(height);
    sc.swapchain_textures = g_swapchains;
    sc.swapchain_length = 1;
    sc.enable_foveation = g_foveation_on;
    sc.foveated_encoding = g_foveation;
    sc.enable_upscaling = false;
    alvr_start_stream_opengl(sc);
}

/**
 * Each eye's view, in the head's frame: [ipd_m] apart and [fovs] as left, right, up, down angles in radians, left eye
 * first (8 floats). Sent when they change.
 */
JNIEXPORT void JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativeSendViewParams(
    JNIEnv *env, jclass, jfloat ipd_m, jfloatArray fovs) {
    float f[8];
    env->GetFloatArrayRegion(fovs, 0, 8, f);
    const AlvrQuat ident{0, 0, 0, 1};
    AlvrViewParams v[2];
    v[0].pose = eye_pose(ident, -ipd_m / 2.0f);
    v[0].fov = fov_of(f);
    v[1].pose = eye_pose(ident, ipd_m / 2.0f);
    v[1].fov = fov_of(f + 4);
    alvr_send_view_params(v);
}

/**
 * The head at [target_ns] (System.nanoTime, the time the pose was predicted for): orientation x, y, z, w, position
 * x, y, z in the play space (metres), angular velocity x, y, z (rad/s, world). 10 floats.
 */
JNIEXPORT void JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativeSendHead(
    JNIEnv *env, jclass, jlong target_ns, jfloatArray head) {
    float h[10];
    env->GetFloatArrayRegion(head, 0, 10, h);
    AlvrDeviceMotion m{};
    m.device_id = g_head_id;
    m.pose.orientation = quat_of(h);
    m.pose.position[0] = h[4];
    m.pose.position[1] = h[5];
    m.pose.position[2] = h[6];
    m.angular_velocity[0] = h[7];
    m.angular_velocity[1] = h[8];
    m.angular_velocity[2] = h[9];
    alvr_send_tracking(static_cast<uint64_t>(target_ns), &m, 1, nullptr, nullptr);
}

/** GL thread: the lobby (ALVR's room with its HUD text) for a head [orientation] (4 floats), eyes [ipd_m] apart. */
JNIEXPORT void JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativeRenderLobby(
    JNIEnv *env, jclass, jfloatArray orientation, jfloat ipd_m, jfloatArray fovs) {
    float q[4];
    float f[8];
    env->GetFloatArrayRegion(orientation, 0, 4, q);
    env->GetFloatArrayRegion(fovs, 0, 8, f);
    const AlvrQuat head = quat_of(q);
    AlvrLobbyViewParams v[2];
    v[0].swapchain_index = 0;
    v[0].view_params.pose = eye_pose(head, -ipd_m / 2.0f);
    v[0].view_params.fov = fov_of(f);
    v[1].swapchain_index = 0;
    v[1].view_params.pose = eye_pose(head, ipd_m / 2.0f);
    v[1].view_params.fov = fov_of(f + 4);
    alvr_render_lobby_opengl(v, true);
}

/**
 * GL thread: the newest decoded frame drawn into the eye textures, or nothing new. Returns its timestamp (the pose
 * time it was rendered for), or 0 with none. [out_pose] gets the head orientation the PC rendered it with (4 floats).
 * [now_orientation] is the head now (4 floats), used to turn the picture to it (reprojection). [vsync_queue_ns] is how
 * long until it is shown, for ALVR's statistics.
 */
JNIEXPORT jlong JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativeRenderFrame(
    JNIEnv *env, jclass, jfloatArray now_orientation, jlong vsync_queue_ns, jfloatArray out_pose) {
    uint64_t ts = 0;
    void *buffer = nullptr;
    if (!alvr_get_frame(&ts, &buffer)) return 0;
    AlvrVideoFrameMetadata meta{};
    const bool has_meta = alvr_report_compositor_start(ts, &meta);
    float q[4];
    env->GetFloatArrayRegion(now_orientation, 0, 4, q);
    const AlvrQuat now = quat_of(q);
    AlvrStreamViewParams v[2];
    for (int eye = 0; eye < 2; ++eye) {
        v[eye].swapchain_index = 0;
        v[eye].reprojection_rotation =
            has_meta ? alvr_rotation_delta(meta.view_params[eye].pose.orientation, now) : AlvrQuat{0, 0, 0, 1};
        v[eye].fov = meta.view_params[eye].fov;
    }
    alvr_render_stream_opengl(buffer, v, has_meta && meta.has_foveation_centers ? &meta.foveation_center_shifts : nullptr);
    alvr_report_submit(ts, static_cast<uint64_t>(vsync_queue_ns));
    if (has_meta) {
        const AlvrQuat &r = meta.view_params[0].pose.orientation;
        const float p[4] = {r.x, r.y, r.z, r.w};
        env->SetFloatArrayRegion(out_pose, 0, 4, p);
    }
    return static_cast<jlong>(ts);
}

namespace {
EGLImageKHR g_image = EGL_NO_IMAGE_KHR;
}

/**
 * GL thread: the newest decoded frame bound to the external texture [oes_texture] (both eyes side by side), or nothing
 * new. Returns its timestamp (the pose time it was rendered for), or 0 with none. [out_pose] gets the head orientation
 * the PC rendered it with and each eye's view (12 floats). The previous frame's buffer goes back to the decoder when a new one comes.
 */
JNIEXPORT jlong JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativeAcquireFrame(
    JNIEnv *env, jclass, jint oes_texture, jfloatArray out_pose) {
    uint64_t ts = 0;
    void *buffer = nullptr;
    if (!alvr_get_frame(&ts, &buffer) || buffer == nullptr) return 0;
    EGLDisplay display = eglGetCurrentDisplay();
    if (g_image != EGL_NO_IMAGE_KHR) {
        eglDestroyImageKHR(display, g_image);
        g_image = EGL_NO_IMAGE_KHR;
    }
    EGLClientBuffer client = eglGetNativeClientBufferANDROID(static_cast<const AHardwareBuffer *>(buffer));
    const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    g_image = eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attrs);
    if (g_image == EGL_NO_IMAGE_KHR) {
        LOGW("frame %llu: no EGL image (0x%x)", static_cast<unsigned long long>(ts), eglGetError());
        return 0;
    }
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, static_cast<GLuint>(oes_texture));
    glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, static_cast<GLeglImageOES>(g_image));
    AlvrVideoFrameMetadata meta{};
    if (alvr_report_compositor_start(ts, &meta)) {
        // The head orientation the PC drew with, then each eye's view (left, right, up, down angles), left eye first.
        const AlvrQuat &r = meta.view_params[0].pose.orientation;
        const AlvrFov &l = meta.view_params[0].fov;
        const AlvrFov &g = meta.view_params[1].fov;
        const float p[12] = {r.x, r.y, r.z, r.w, l.left, l.right, l.up, l.down, g.left, g.right, g.up, g.down};
        env->SetFloatArrayRegion(out_pose, 0, 12, p);
    }
    return static_cast<jlong>(ts);
}

/** How far ahead to predict the head sent with nativeSendHead: ALVR's measured delay (0 before a stream). */
JNIEXPORT jlong JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativePredictionOffsetNs(JNIEnv *, jclass) {
    return static_cast<jlong>(alvr_get_total_prediction_offset_ns());
}

/** After the frame [timestamp_ns] was drawn: [vsync_queue_ns] until it is shown, for ALVR's statistics. */
JNIEXPORT void JNICALL Java_com_hayeco_flipspaces_pcvr_AlvrBridge_nativeSubmitFrame(
    JNIEnv *, jclass, jlong timestamp_ns, jlong vsync_queue_ns) {
    alvr_report_submit(static_cast<uint64_t>(timestamp_ns), static_cast<uint64_t>(vsync_queue_ns));
}

}  // extern "C"
