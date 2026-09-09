cmake_minimum_required(VERSION 3.22.1)
project(tunneljni C)

include(ExternalProject)

set(HEV_C_FLAGS "${CMAKE_C_FLAGS} --target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT} -fPIC -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY")
# ^ WAJIB: kita panggil `make` sebagai proses eksternal, bukan lewat compile
# rule CMake sendiri -- jadi flag --sysroot DAN --target yang biasanya
# otomatis disisipkan CMake TIDAK ikut terbawa. Tanpa --target, clang tidak
# tahu harus cari header di folder arch-spesifik (mis. sysroot/usr/include/
# aarch64-linux-android/), sehingga header seperti asm/types.h tidak ketemu
# meskipun --sysroot sudah benar.
#
# -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY: PERSIS flag yang
# dipakai Android.mk RESMI hev-socks5-tunnel (bukan tebakan) -- mencegah
# bentrok typedef fd_set antara lwIP dan header Android/Bionic.

# Path source & prefix DITENTUKAN SENDIRI secara eksplisit (bukan ditebak lewat
# ExternalProject_Get_Property), supaya tidak ada ambiguitas seperti error
# sebelumnya ("-build" suffix yang tidak terduga).
set(HEV_PREFIX ${CMAKE_CURRENT_BINARY_DIR}/hev-src)
set(HEV_SOURCE_DIR ${HEV_PREFIX}/hev-socks5-tunnel)

ExternalProject_Add(
    hev_socks5_tunnel_external
    PREFIX ${HEV_PREFIX}
    SOURCE_DIR ${HEV_SOURCE_DIR}
    GIT_REPOSITORY https://github.com/heiher/hev-socks5-tunnel.git
    GIT_TAG main
    GIT_SUBMODULES_RECURSE ON
    CONFIGURE_COMMAND ""
    BUILD_COMMAND make static
        CC=${CMAKE_C_COMPILER}
        AR=${CMAKE_AR}
        STRIP=${CMAKE_STRIP}
        "CFLAGS=${HEV_C_FLAGS}"
        CROSS_PREFIX=${ANDROID_TOOLCHAIN_PREFIX}
    BUILD_IN_SOURCE 1
    INSTALL_COMMAND ""
    LOG_BUILD ON
    LOG_OUTPUT_ON_FAILURE ON
    # WAJIB untuk generator Ninja: tanpa ini, ninja tidak tahu bahwa file .a
    # ini "akan" dihasilkan oleh step BUILD_COMMAND di atas, dan langsung
    # menyerah dengan "missing and no known rule to make it" -- padahal
    # step-nya belum sempat dijalankan sama sekali.
    BUILD_BYPRODUCTS
        ${HEV_SOURCE_DIR}/bin/libhev-socks5-tunnel.a
        ${HEV_SOURCE_DIR}/third-part/yaml/bin/libyaml.a
        ${HEV_SOURCE_DIR}/third-part/lwip/bin/liblwip.a
        ${HEV_SOURCE_DIR}/third-part/hev-task-system/bin/libhev-task-system.a
)

add_library(hev-socks5-tunnel-core STATIC IMPORTED)
set_target_properties(hev-socks5-tunnel-core PROPERTIES
    IMPORTED_LOCATION ${HEV_SOURCE_DIR}/bin/libhev-socks5-tunnel.a
)

add_library(hev-yaml STATIC IMPORTED)
set_target_properties(hev-yaml PROPERTIES
    IMPORTED_LOCATION ${HEV_SOURCE_DIR}/third-part/yaml/bin/libyaml.a
)

add_library(hev-lwip STATIC IMPORTED)
set_target_properties(hev-lwip PROPERTIES
    IMPORTED_LOCATION ${HEV_SOURCE_DIR}/third-part/lwip/bin/liblwip.a
)

add_library(hev-task-system STATIC IMPORTED)
set_target_properties(hev-task-system PROPERTIES
    IMPORTED_LOCATION ${HEV_SOURCE_DIR}/third-part/hev-task-system/bin/libhev-task-system.a
)

add_library(tunneljni SHARED hev_socks5_jni.c)
add_dependencies(tunneljni hev_socks5_tunnel_external)

target_include_directories(tunneljni PRIVATE
    ${HEV_SOURCE_DIR}/src
)

target_link_libraries(tunneljni
    hev-socks5-tunnel-core
    hev-lwip
    hev-yaml
    hev-task-system
    log
)
