cmake_minimum_required(VERSION 3.22.1)
project(cinematic_dng LANGUAGES CXX)

include(FetchContent)
# Unmodified official release retained in the repository. CDDL-1.0 selected.
FetchContent_Declare(libraw
    URL "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/libraw/LibRaw-0.22.3.tar.gz"
    URL_HASH SHA256=789f3c33466210a1a0907f747a97c715d21bbf4af712f257fcbbd90111b227e4)
FetchContent_GetProperties(libraw)
if(NOT libraw_POPULATED)
    FetchContent_Populate(libraw)
endif()
file(GLOB_RECURSE libraw_sources CONFIGURE_DEPENDS "${libraw_SOURCE_DIR}/src/*.cpp")
add_library(raw STATIC ${libraw_sources})
set_target_properties(raw PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(raw PUBLIC "${libraw_SOURCE_DIR}")
target_compile_definitions(raw PRIVATE LIBRAW_NOTHREADS USE_ZLIB)
target_compile_features(raw PUBLIC cxx_std_17)
# Android zlib only. No JPEG/LCMS/RawSpeed/DNG SDK/GPL demosaic packs/OpenMP.
target_link_libraries(raw PUBLIC z)

add_library(cinematic_dng SHARED dng_decoder.cpp w1_trial.cpp as_shot_xy.cpp original_raster_sink.cpp)
target_compile_options(cinematic_dng PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
# Keep static LibRaw/C++ runtime symbols private to this JNI boundary.
target_link_options(cinematic_dng PRIVATE "-Wl,--exclude-libs,ALL")
option(CINEMATIC_ENABLE_RAW_PROBE "Build the isolated full-resolution diagnostic JNI entry" OFF)
set(dng_exports "${CMAKE_CURRENT_LIST_DIR}/dng.exports")
if(CINEMATIC_ENABLE_RAW_PROBE)
    target_compile_definitions(cinematic_dng PRIVATE CINEMATIC_ENABLE_RAW_PROBE)
    target_sources(cinematic_dng PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../../../rawprobe/src/main/cpp/baseline_small.cpp")
    target_include_directories(cinematic_dng PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../../../rawprobe/src/main/cpp")
    target_link_libraries(cinematic_dng PRIVATE jnigraphics)
    set(dng_exports "${CMAKE_CURRENT_LIST_DIR}/probe.exports")
endif()
target_link_options(cinematic_dng PRIVATE "-Wl,--version-script=${dng_exports}")
set_property(TARGET cinematic_dng APPEND PROPERTY LINK_DEPENDS "${dng_exports}")
target_link_libraries(cinematic_dng PRIVATE raw)
