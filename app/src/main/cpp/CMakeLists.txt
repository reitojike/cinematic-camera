cmake_minimum_required(VERSION 3.22.1)
project(cinematic_dng LANGUAGES CXX)

include(FetchContent)
# Unmodified official release. CDDL-1.0 selected; notices and source URL ship in assets.
FetchContent_Declare(libraw
    URL https://www.libraw.org/data/LibRaw-0.22.3.tar.gz
    TLS_VERIFY TRUE
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

add_library(cinematic_dng SHARED dng_decoder.cpp)
target_compile_options(cinematic_dng PRIVATE -Wall -Wextra -Werror)
target_link_libraries(cinematic_dng PRIVATE raw)
