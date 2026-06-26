cmake_minimum_required(VERSION 3.22.1)
project("yoloseg")

# ─── Build flags ──────────────────────────────────────────────
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_compile_options(-O2 -fvisibility=hidden)

# ─── Paths ────────────────────────────────────────────────────
set(NCNN_DIR   "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/ncnn/${ANDROID_ABI}")
set(OPENCV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/opencv/${ANDROID_ABI}")

# ─── NCNN (static) ────────────────────────────────────────────
add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION "${NCNN_DIR}/lib/libncnn.a"
    INTERFACE_INCLUDE_DIRECTORIES "${NCNN_DIR}/include/ncnn"
)

# ─── OpenCV Mobile (static libs) ──────────────────────────────
add_library(opencv_imgproc  STATIC IMPORTED)
set_target_properties(opencv_imgproc  PROPERTIES IMPORTED_LOCATION "${OPENCV_DIR}/lib/libopencv_imgproc.a")

add_library(opencv_core     STATIC IMPORTED)
set_target_properties(opencv_core     PROPERTIES IMPORTED_LOCATION "${OPENCV_DIR}/lib/libopencv_core.a")

add_library(opencv_features2d STATIC IMPORTED)
set_target_properties(opencv_features2d PROPERTIES IMPORTED_LOCATION "${OPENCV_DIR}/lib/libopencv_features2d.a")

# arm64-v8a only — kleidicv acceleration libs
if(ANDROID_ABI STREQUAL "arm64-v8a")
    foreach(KLIB kleidicv kleidicv_hal kleidicv_thread)
        set(kpath "${OPENCV_DIR}/lib/lib${KLIB}.a")
        if(EXISTS ${kpath})
            add_library(${KLIB} STATIC IMPORTED)
            set_target_properties(${KLIB} PROPERTIES IMPORTED_LOCATION ${kpath})
        endif()
    endforeach()
endif()

# ─── OpenCV include dirs ───────────────────────────────────────
include_directories("${OPENCV_DIR}/include")

# ─── Android system libs ──────────────────────────────────────
find_library(log-lib      log)
find_library(android-lib  android)
find_library(jnigraphics-lib jnigraphics)
find_library(vulkan-lib   vulkan)
find_library(camera-lib   camera2ndk)
find_library(mediandk-lib mediandk)

# ─── Source files ─────────────────────────────────────────────
add_library(
    yoloseg
    SHARED
    yoloseg_jni.cpp
    YoloSeg.cpp
    ndkcamera.cpp
    fix_stringstream.cpp
    glslang_stubs.cpp
)

# ─── Include dirs ─────────────────────────────────────────────
target_include_directories(yoloseg PRIVATE
    ${NCNN_DIR}/include/ncnn
    ${OPENCV_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# ─── Link ─────────────────────────────────────────────────────
find_package(OpenMP REQUIRED)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Link kleidicv if they exist
    set(KLEIDI_LIBS "")
    foreach(KLIB kleidicv kleidicv_hal kleidicv_thread)
        if(TARGET ${KLIB})
            list(APPEND KLEIDI_LIBS ${KLIB})
        endif()
    endforeach()
else()
    set(KLEIDI_LIBS "")
endif()

target_link_libraries(yoloseg
    ncnn
    opencv_imgproc
    opencv_core
    opencv_features2d
    ${KLEIDI_LIBS}
    OpenMP::OpenMP_CXX
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    ${vulkan-lib}
    ${camera-lib}
    ${mediandk-lib}
    z
    dl
    m
    atomic
)
