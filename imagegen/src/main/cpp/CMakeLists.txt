cmake_minimum_required(VERSION 3.22.1)
project("lia_sd_bridge" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --------------------------------------------------------------------------
# Own Gradle module and own CMake project, never merged into :app's
# llama.cpp build: stable-diffusion.cpp vendors its own ggml (leejet's fork,
# pinned as a submodule) and both projects declare an `ggml` target, which
# fails in a single configure pass.
#
# Everything here is STATIC (sd.cpp's default) and linked into one
# libliasd.so with --exclude-libs,ALL: :app ships llama.cpp's ggml as shared
# libggml*.so, and a second, different libggml.so in the APK would silently
# replace one with the other at packaging time. Statically linked and hidden,
# this ggml copy can never be confused with llama.cpp's at runtime.
#
# Pulled at configure time (CI has network access) and pinned to an exact
# commit: the JNI bridge is written against stable-diffusion.h at this commit.
# --------------------------------------------------------------------------
include(FetchContent)
FetchContent_Declare(
    stable_diffusion_cpp
    GIT_REPOSITORY https://github.com/leejet/stable-diffusion.cpp.git
    GIT_TAG        a1ded76da5818803fca97a3b433669ef727d32cf
    GIT_SUBMODULES "ggml"
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# CPU only for liasd and liasd_fast, like :app's llama.cpp build (Vulkan
# caused a native crash on the real test device there). The third build,
# liasd_gpu (:imagegen-gpu, LIA_SD_GPU=ON), adds the Vulkan backend: loaded
# only when « GPU (essai) » is on in Réglages Images.
option(LIA_SD_GPU "Build liasd_gpu with the Vulkan backend" OFF)
if (LIA_SD_GPU)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    # The NDK has the C Vulkan headers only: vulkan.hpp and the SPIR-V
    # headers come from Khronos, pinned. Headers only, nothing is built.
    include(FetchContent)
    FetchContent_Declare(vulkan_headers
        GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
        GIT_TAG        v1.4.328)
    FetchContent_Declare(spirv_headers
        GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
        GIT_TAG        fbf86836a9b1966586faf7be7bb8a6440e156d62)
    FetchContent_GetProperties(vulkan_headers)
    if (NOT vulkan_headers_POPULATED)
        FetchContent_Populate(vulkan_headers)
    endif()
    FetchContent_GetProperties(spirv_headers)
    if (NOT spirv_headers_POPULATED)
        FetchContent_Populate(spirv_headers)
    endif()
    set(Vulkan_INCLUDE_DIR "${vulkan_headers_SOURCE_DIR}/include" CACHE PATH "" FORCE)
    set(Vulkan_LIBRARY "${CMAKE_SYSROOT}/usr/lib/${CMAKE_LIBRARY_ARCHITECTURE}/${ANDROID_PLATFORM_LEVEL}/libvulkan.so" CACHE FILEPATH "" FORCE)
    if (NOT EXISTS "${Vulkan_LIBRARY}")
        set(Vulkan_LIBRARY "${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/${ANDROID_PLATFORM_LEVEL}/libvulkan.so" CACHE FILEPATH "" FORCE)
    endif()
    # The NDK's glslc is too old for ggml's cooperative-matrix shaders (they
    # fail to compile): a host glslc from shaderc 2023.8 or later is needed
    # (apt install glslc).
    find_program(LIA_HOST_GLSLC NAMES glslc PATHS /usr/bin /usr/local/bin NO_CMAKE_FIND_ROOT_PATH NO_DEFAULT_PATH)
    if (NOT LIA_HOST_GLSLC)
        message(FATAL_ERROR "liasd_gpu needs a recent host glslc (apt install glslc)")
    endif()
    set(Vulkan_GLSLC_EXECUTABLE "${LIA_HOST_GLSLC}" CACHE FILEPATH "" FORCE)
    # A minimal SPIRV-Headers package for ggml-vulkan's find_package().
    set(LIA_SPIRV_CONFIG_DIR "${CMAKE_BINARY_DIR}/spirv-headers-config")
    file(WRITE "${LIA_SPIRV_CONFIG_DIR}/SPIRV-HeadersConfig.cmake"
        "if (NOT TARGET SPIRV-Headers::SPIRV-Headers)\n"
        "  add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)\n"
        "  set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES INTERFACE_INCLUDE_DIRECTORIES \"${spirv_headers_SOURCE_DIR}/include\")\n"
        "endif()\n")
    set(SPIRV-Headers_DIR "${LIA_SPIRV_CONFIG_DIR}" CACHE PATH "" FORCE)
    include_directories(SYSTEM "${spirv_headers_SOURCE_DIR}/include")
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

# Built twice: here (liasd, any 64-bit ARM) and from the :imagegen-fast module
# with LIA_SD_FAST=ON (liasd_fast, dot-product + half-precision instructions).
# With GGML_NATIVE off and nothing else, ggml targets the oldest ARMv8.0 and
# never uses them — one step of a 512x768 picture took 2 min 30 on a
# Snapdragon 6 Gen 4. StableDiffusionNative loads the fast one when the
# phone's CPU lists both features (asimddp, asimdhp).
option(LIA_SD_FAST "Build liasd_fast for ARMv8.2 dotprod + fp16" OFF)
if (LIA_SD_GPU)
    # Vulkan-capable phones are ARMv8.2 ones: same CPU part as liasd_fast.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    set(LIA_SD_LIB liasd_gpu)
elseif (LIA_SD_FAST)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    set(LIA_SD_LIB liasd_fast)
else()
    set(LIA_SD_LIB liasd)
endif()

# sd.cpp has very large translation units; Ninja's default parallelism
# (cores + 2) got the CI runner killed mid-build (memory pressure). Cap it.
set_property(GLOBAL APPEND PROPERTY JOB_POOLS sd_compile=2 sd_link=1)
set(CMAKE_JOB_POOL_COMPILE sd_compile)
set(CMAKE_JOB_POOL_LINK sd_link)

FetchContent_MakeAvailable(stable_diffusion_cpp)

add_library(${LIA_SD_LIB} SHARED
    sd_bridge.cpp
)

target_link_libraries(${LIA_SD_LIB}
    stable-diffusion
    android
    log
)

target_link_options(${LIA_SD_LIB} PRIVATE "-Wl,--exclude-libs,ALL")

if (LIA_SD_GPU)
    target_compile_definitions(${LIA_SD_LIB} PRIVATE LIA_SD_GPU=1)
endif()
