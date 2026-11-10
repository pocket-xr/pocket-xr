# 0.5.58: each Space frame's own display present time, from EGL_ANDROID_get_frame_timestamps.
cmake_minimum_required(VERSION 3.22.1)
project(flipspaces_frametimes CXX)

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
