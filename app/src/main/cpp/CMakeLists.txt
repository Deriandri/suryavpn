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

add_library(tunneljni SHARED hev_socks5_jni.c config_lock_jni.c)
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

# =============================================================================
# badvpn-tun2socks (engine tunnel KEDUA -- lihat BadVpnEngine.kt/TunEngineRouter.kt)
# =============================================================================
# CATATAN JUJUR (WAJIB DIBACA sebelum build/rilis, sama semangatnya dengan
# blok REVERT di HevSocks5Engine.kt): saya menyusun bagian ini TANPA akses
# jaringan untuk benar-benar clone github.com/ambrop72/badvpn dan mencocokkan
# nama opsi CMake-nya baris per baris -- ini beda dengan blok hev-socks5-tunnel
# di atas (yang polanya sudah lama ada & terbukti jalan di project ini). Yang
# saya tulis di bawah berdasarkan struktur project badvpn yang saya ingat
# (dia SUDAH pakai CMake asli, bukan Makefile mentah seperti hev, jadi
# forward toolchain NDK jauh lebih langsung -- tidak perlu trik CFLAGS manual
# macam HEV_C_FLAGS di atas). WAJIB diverifikasi sebelum dipakai:
#   1. Opsi `BUILDING_NOTHING_BY_DEFAULT` / `BUILD_TUN2SOCKS` ada di
#      CMakeLists.txt resmi badvpn per commit yang di-checkout (`GIT_TAG`
#      di bawah pin ke tag rilis, BUKAN `main`, justru supaya opsi ini tidak
#      diam-diam berubah nama seperti resiko yang sudah pernah kejadian di
#      komentar REVERT hev-socks5-tunnel).
#   2. Nama file hasil build (diasumsikan `tun2socks/badvpn-tun2socks` di
#      dalam build dir) -- cek log build kalau step copy di bawah gagal
#      "file tidak ditemukan".
#   3. Argumen CLI yang dipakai BadVpnEngine.kt (--tundev, --netif-ipaddr,
#      dst.) -- lihat catatan di file itu, cocokkan ke `--help` binary hasil
#      build ini.
# Kalau ADA yang meleset, build APK akan gagal jelas di step ExternalProject
# ini (bukan diam-diam salah jalan) -- jadi aman untuk dicoba build dulu,
# tinggal baca error log-nya kalau merah.

set(BADVPN_PREFIX ${CMAKE_CURRENT_BINARY_DIR}/badvpn-src)
set(BADVPN_SOURCE_DIR ${BADVPN_PREFIX}/badvpn)
set(BADVPN_BUILD_DIR ${BADVPN_PREFIX}/badvpn-build)
# Nama file OUTPUT sengaja berpola "lib*.so" (walau ISINYA native EXECUTABLE,
# bukan shared library beneran) supaya Android mau mengekstraknya ke
# nativeLibraryDir device (jadi bisa dieksekusi lewat ProcessBuilder tanpa
# root) -- ini teknik umum yang dipakai banyak app tunnel Android lain utk
# membundel binary tun2socks/udpgw sejenis. Ditaruh langsung di
# app/src/main/jniLibs/<ABI>/ (source set default Gradle, SELALU dipaketkan
# apa adanya, independen dari mekanisme discovery target CMake-nya AGP)
# supaya tidak bergantung ke AGP mau "menemukan" file hasil ExternalProject
# yang bukan actual CMake library target.
set(BADVPN_JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
set(BADVPN_OUTPUT_SO ${BADVPN_JNILIBS_DIR}/libbadvpn-tun2socks.so)
# Path binary hasil build badvpn SEBELUM di-copy -- asumsi struktur output
# project badvpn (folder per-komponen di dalam build dir), lihat poin 2 di
# catatan atas kalau ternyata beda.
set(BADVPN_BUILT_BINARY ${BADVPN_BUILD_DIR}/tun2socks/badvpn-tun2socks)

ExternalProject_Add(
    badvpn_tun2socks_external
    PREFIX ${BADVPN_PREFIX}
    SOURCE_DIR ${BADVPN_SOURCE_DIR}
    BINARY_DIR ${BADVPN_BUILD_DIR}
    GIT_REPOSITORY https://github.com/ambrop72/badvpn.git
    # Di-pin ke tag rilis (bukan HEAD/main) SENGAJA -- lihat poin 1 di
    # catatan atas. Ganti ke commit hash spesifik kalau tag ini ternyata
    # sudah tidak ada di remote saat kamu build.
    GIT_TAG 1.999.130
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_NDK=${ANDROID_NDK}
        -DCMAKE_BUILD_TYPE=Release
        # Badvpn satu repo berisi BANYAK komponen (ncd, badvpn-server, dll)
        # yang sebagian besar butuh dependency Linux-only (Linux netlink,
        # dll) tidak relevan/tidak bisa dicompile utk Android -- dua flag
        # ini (nama sesuai poin 1 di atas) membatasi build HANYA ke
        # tun2socks supaya tidak ikut gagal compile komponen yang tidak
        # dipakai app ini sama sekali.
        -DBUILDING_NOTHING_BY_DEFAULT=1
        -DBUILD_TUN2SOCKS=1
        -DBUILD_NOTHING=0
    INSTALL_COMMAND ""
    LOG_BUILD ON
    LOG_OUTPUT_ON_FAILURE ON
    BUILD_BYPRODUCTS ${BADVPN_BUILT_BINARY}
)

add_custom_command(
    OUTPUT ${BADVPN_OUTPUT_SO}
    COMMAND ${CMAKE_COMMAND} -E make_directory ${BADVPN_JNILIBS_DIR}
    COMMAND ${CMAKE_COMMAND} -E copy ${BADVPN_BUILT_BINARY} ${BADVPN_OUTPUT_SO}
    # chmod +x eksplisit -- `cmake -E copy` TIDAK selalu ikut membawa bit
    # executable sumbernya tergantung platform host build, dan APK packaging
    # sendiri butuh source file-nya sudah executable di beberapa jalur AGP.
    COMMAND ${CMAKE_COMMAND} -E chmod +x ${BADVPN_OUTPUT_SO}
    DEPENDS badvpn_tun2socks_external
    COMMENT "Menyalin badvpn-tun2socks ke jniLibs/${ANDROID_ABI}/ sebagai libbadvpn-tun2socks.so"
    VERBATIM
)
add_custom_target(badvpn_tun2socks_copy ALL DEPENDS ${BADVPN_OUTPUT_SO})

# tunneljni HARUS "menyentuh" target ini (add_dependencies) -- BUKAN
# mengandalkan target custom "ALL" di atas, karena AGP kadang memanggil
# `cmake --build --target tunneljni` secara spesifik (bukan target default
# proyek), yang akan melewatkan target ALL lain kalau tidak ada relasi
# dependency eksplisit macam ini.
add_dependencies(tunneljni badvpn_tun2socks_copy)
