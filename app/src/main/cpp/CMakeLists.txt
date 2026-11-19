# 0.5.58: each Space frame's own display present time, from EGL_ANDROID_get_frame_timestamps.
cmake_minimum_required(VERSION 3.22.1)
project(flipspaces_frametimes C CXX)

add_library(flipspaces_frametimes SHARED frame_timestamps.cpp)
target_link_libraries(flipspaces_frametimes EGL log)

# PC VR in the Space: the bridge to ALVR's client library (MIT licence), built from ALVR's source with cargo-ndk and
# kept in jniLibs with the C++ runtime it links (docs/PCVR-PLAN.md).
# Built for arm64 only, so other ABIs (the x86_64 emulator) leave the bridge out.
set(ALVR_LIB ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libalvr_client_core.so)
if(EXISTS ${ALVR_LIB})
    add_library(alvr_client_core SHARED IMPORTED)
    # Rust builds it with no soname: link it by name, or the build path ends up in DT_NEEDED.
    set_target_properties(alvr_client_core PROPERTIES IMPORTED_LOCATION ${ALVR_LIB} IMPORTED_NO_SONAME TRUE)
    add_library(pocketxr_alvr SHARED pocketxr_alvr.cpp)
    target_include_directories(pocketxr_alvr PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(pocketxr_alvr alvr_client_core EGL GLESv2 android log)
endif()

# PC screens: Moonlight's streaming core (GPLv3, like Pocket XR) from moonlight-android's jni/moonlight-core, with
# moonlight-common-c at the commit moonlight-android pins (874ac95) and its prebuilt OpenSSL libcrypto and Opus
# (moonlight/README.md). Loaded as libmoonlight-core.so by com.limelight.nvstream.jni.MoonBridge.
set(ML ${CMAKE_CURRENT_SOURCE_DIR}/moonlight)
set(MLC ${ML}/moonlight-common-c)
add_library(ml_crypto STATIC IMPORTED)
set_target_properties(ml_crypto PROPERTIES IMPORTED_LOCATION ${ML}/openssl/${ANDROID_ABI}/libcrypto.a)
add_library(ml_opus STATIC IMPORTED)
set_target_properties(ml_opus PROPERTIES IMPORTED_LOCATION ${ML}/opus/${ANDROID_ABI}/libopus.a)
add_library(ml_cpufeatures STATIC ${ANDROID_NDK}/sources/android/cpufeatures/cpu-features.c)
target_include_directories(ml_cpufeatures PUBLIC ${ANDROID_NDK}/sources/android/cpufeatures)
file(GLOB ML_COMMON_SRC ${MLC}/src/*.c)
add_library(moonlight-core SHARED
    ${ML_COMMON_SRC}
    ${MLC}/nanors/deps/obl/oblas_common.c ${MLC}/nanors/deps/obl/oblas_lite.c ${MLC}/nanors/rs.c
    ${MLC}/enet/callbacks.c ${MLC}/enet/compress.c ${MLC}/enet/host.c ${MLC}/enet/list.c ${MLC}/enet/packet.c
    ${MLC}/enet/peer.c ${MLC}/enet/protocol.c ${MLC}/enet/unix.c
    ${ML}/simplejni.c ${ML}/callbacks.c ${ML}/minisdl.c)
target_include_directories(moonlight-core PRIVATE ${MLC}/enet/include ${MLC}/nanors/deps/obl ${MLC}/nanors ${MLC}/src
    ${ML}/openssl/include ${ML}/opus/include ${ML})
target_compile_definitions(moonlight-core PRIVATE HAS_SOCKLEN_T=1 LC_ANDROID HAVE_CLOCK_GETTIME=1)
target_link_libraries(moonlight-core ml_opus ml_crypto ml_cpufeatures log)
target_link_options(moonlight-core PRIVATE -Wl,--exclude-libs,ALL)
