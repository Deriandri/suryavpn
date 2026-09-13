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
#   1. Opsi `BUILD_NOTHING_BY_DEFAULT` / `BUILD_TUN2SOCKS` ada di
#      CMakeLists.txt resmi badvpn per commit yang di-checkout (`GIT_TAG`
#      di bawah pin ke tag rilis, BUKAN `main`, justru supaya opsi ini tidak
#      diam-diam berubah nama seperti resiko yang sudah pernah kejadian di
#      komentar REVERT hev-socks5-tunnel). CATATAN: build sebelumnya sempat
#      gagal justru karena typo di flag ini sendiri (`BUILDING_...` bukan
#      `BUILD_...`) -- lihat komentar FIX di dekat CMAKE_ARGS di bawah.
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

# =============================================================================
# STUB librt.a -- WAJIB sebelum ExternalProject_Add badvpn di bawah
# =============================================================================
# FIX (error build nyata: "ld.lld: error: unable to find library -lrt" saat
# linking badvpn-tun2socks): CMakeLists.txt resmi badvpn punya baris TANPA
# syarat `link_libraries(rt pthread)` -- otomatis nempel ke semua target,
# termasuk tun2socks. Di Linux biasa, `librt` adalah library terpisah utk
# fungsi POSIX realtime (mis. clock_gettime, timer_create). Tapi di
# Android/Bionic, `librt` TIDAK PERNAH ada sebagai file terpisah -- semua
# fungsi itu sudah lama digabung ke dalam libc.so bawaan Android, jadi NDK
# tidak menyediakan librt.a/.so apa pun di sysroot-nya (beda dengan
# `pthread`, yang NDK sediakan stub-nya -- itu sebabnya hanya `-lrt` yang
# error, bukan `-lpthread`).
#
# Karena badvpn di-clone otomatis dari GitHub upstream (bukan source yang
# kita commit sendiri), CMakeLists.txt-nya TIDAK BISA kita edit langsung.
# Solusinya: buat file "librt.a" kosong (arsip tanpa isi -- symbol rt yang
# dibutuhkan sudah otomatis resolve dari libc Android saat link), taruh di
# folder sendiri, lalu arahkan linker badvpn ke folder itu lewat
# CMAKE_EXE_LINKER_FLAGS (-L). Ini teknik umum yang dipakai beberapa fork
# Android badvpn lain untuk masalah yang sama.
set(RT_STUB_DIR ${CMAKE_CURRENT_BINARY_DIR}/rt-stub)
set(RT_STUB_LIB ${RT_STUB_DIR}/librt.a)
add_custom_command(
    OUTPUT ${RT_STUB_LIB}
    COMMAND ${CMAKE_COMMAND} -E make_directory ${RT_STUB_DIR}
    COMMAND ${CMAKE_AR} rcs ${RT_STUB_LIB}
    COMMENT "Membuat stub librt.a kosong (Android tidak punya librt terpisah, fungsi rt sudah built-in di libc)"
    VERBATIM
)
add_custom_target(rt_stub DEPENDS ${RT_STUB_LIB})

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
        # Lihat blok STUB librt.a di atas -- ini yang membuat linker
        # menemukan "-lrt" walau isinya kosong (aman, karena Android tidak
        # butuh symbol apa pun dari librt sungguhan).
        "-DCMAKE_EXE_LINKER_FLAGS=-L${RT_STUB_DIR}"
        # FIX (error build nyata: "if given arguments: hwaddress IN_LIST
        # ANDROID_SANITIZE -- Unknown arguments specified", CMake Error di
        # android.toolchain.cmake/flags.cmake milik NDK sendiri): badvpn
        # punya `cmake_minimum_required(VERSION ...)` versi LAMA di
        # CMakeLists.txt-nya sendiri, yang me-reset "policy stack" CMake utk
        # scope project itu ke versi compatibility lama -- efeknya CMP0057
        # (yang bikin `if(x IN_LIST y)` dikenali sebagai operator) balik ke
        # OLD (nonaktif) walau CMake yang dipakai sudah baru. flags.cmake
        # milik NDK r27 SENDIRI memakai sintaks `IN_LIST` ini, jadi kalau
        # tidak dipaksa NEW di sini, toolchain file NDK-nya sendiri yang
        # gagal parse -- BUKAN kode badvpn. `CMAKE_POLICY_DEFAULT_CMP0057`
        # berlaku sebagai default utk SEMUA scope project (termasuk yang
        # cmake_minimum_required-nya lebih lama) kalau project itu sendiri
        # tidak eksplisit set kebijakan ini, jadi aman diforward ke sini.
        -DCMAKE_POLICY_DEFAULT_CMP0057=NEW
        # Badvpn satu repo berisi BANYAK komponen (ncd, badvpn-server, dll)
        # yang sebagian besar butuh dependency Linux-only (Linux netlink,
        # dll) tidak relevan/tidak bisa dicompile utk Android -- flag ini
        # membatasi build HANYA ke tun2socks supaya tidak ikut gagal
        # compile komponen yang tidak dipakai app ini sama sekali.
        #
        # FIX (error build nyata: "Could NOT find NSPR" / "Could NOT find
        # NSS" saat configure): nama opsi CMake resmi badvpn adalah
        # `BUILD_NOTHING_BY_DEFAULT` (lihat README ambrop72/badvpn:
        # "-DBUILD_NOTHING_BY_DEFAULT=1 -DBUILD_TUN2SOCKS=1
        # -DBUILD_UDPGW=1"), BUKAN `BUILDING_NOTHING_BY_DEFAULT` yang
        # ditulis sebelumnya di sini -- typo itu membuat CMake mengabaikan
        # flag ini sepenuhnya (opsi tak dikenal tidak error, cuma no-op),
        # sehingga BUILD_CLIENT/BUILD_SERVER/BUILD_FLOODER tetap ikut ON
        # sesuai default masing-masing. Salah satu darinya lah (bukan
        # tun2socks, yang TIDAK butuh NSPR/NSS sama sekali) yang memicu
        # blok `find_package(NSPR REQUIRED)` / `find_package(NSS REQUIRED)`
        # di CMakeLists.txt resmi badvpn. Juga tidak ada opsi `BUILD_NOTHING`
        # di badvpn -- itu ikut dihapus karena tidak berefek apa pun (bukan
        # typo yang berbahaya seperti di atas, tapi tetap tidak ada gunanya).
        -DBUILD_NOTHING_BY_DEFAULT=1
        -DBUILD_TUN2SOCKS=1
        # FIX (error build nyata: "Unknown signal backend or too many
        # signal backends" / "Unknown event backend or too many event
        # backends" saat compile system/BUnixSignal.h & BReactor_badvpn.h):
        # badvpn HANYA auto-set define backend ini
        # (`BADVPN_USE_SIGNALFD`/`BADVPN_USE_SELFPIPE` dan
        # `BADVPN_USE_EPOLL`/`BADVPN_USE_POLL`) kalau
        # `CMAKE_SYSTEM_NAME STREQUAL "Linux"` di CMakeLists.txt resminya.
        # Saat cross-compile pakai NDK, CMAKE_SYSTEM_NAME yang terdeteksi
        # adalah "Android", BUKAN "Linux" -- jadi cabang deteksi itu tidak
        # pernah jalan meskipun kernel Android sebenarnya tetap Linux dan
        # tetap punya signalfd/epoll. Kita forward define itu manual
        # lewat CMAKE_C_FLAGS supaya tidak bergantung ke deteksi OS badvpn
        # yang tidak mengenali "Android" sebagai varian Linux.
        "-DCMAKE_C_FLAGS=-DBADVPN_LINUX -DBADVPN_USE_SIGNALFD -DBADVPN_USE_EPOLL"
    INSTALL_COMMAND ""
    LOG_BUILD ON
    LOG_OUTPUT_ON_FAILURE ON
    BUILD_BYPRODUCTS ${BADVPN_BUILT_BINARY}
)
# Pastikan librt.a stub SUDAH ada sebelum ninja menjalankan build step
# badvpn_tun2socks_external -- tanpa relasi eksplisit ini, ninja boleh
# menjalankan keduanya dalam urutan sembarang (termasuk badvpn duluan),
# yang balik lagi ke error "unable to find library -lrt" di atas.
add_dependencies(badvpn_tun2socks_external rt_stub)

add_custom_command(
    OUTPUT ${BADVPN_OUTPUT_SO}
    COMMAND ${CMAKE_COMMAND} -E make_directory ${BADVPN_JNILIBS_DIR}
    COMMAND ${CMAKE_COMMAND} -E copy ${BADVPN_BUILT_BINARY} ${BADVPN_OUTPUT_SO}
    # chmod +x eksplisit -- `cmake -E copy` TIDAK selalu ikut membawa bit
    # executable sumbernya tergantung platform host build, dan APK packaging
    # sendiri butuh source file-nya sudah executable di beberapa jalur AGP.
    #
    # FIX (error build nyata: "CMake Error: ... Usage: cmake -E <command>
    # [arguments...]" -- chmod tidak ada di daftar "Available commands"):
    # `cmake -E` (command-line/script mode) TIDAK PERNAH punya subcommand
    # "chmod" di versi CMake manapun -- yang mirip itu `file(CHMOD ...)`,
    # tapi itu perintah untuk DALAM skrip CMakeLists (dievaluasi saat
    # configure), bukan mode `-E` yang dieksekusi saat build seperti di
    # sini. Panggil langsung binary `chmod` shell biasa -- aman karena
    # ExternalProject build ini jalan di runner Linux (GitHub Actions).
    COMMAND chmod +x ${BADVPN_OUTPUT_SO}
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
