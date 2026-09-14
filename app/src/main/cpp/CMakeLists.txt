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

# ============================================================================
# FITUR BARU (permintaan user): badvpn-tun2socks sebagai TunEngine KEDUA
# ([com.example.tunnelapp.tunnel.BadVpnTun2socksEngine]), alternatif dari
# hev-socks5-tunnel di atas. BEDA PENDEKATAN dari hev: badvpn TIDAK
# di-wrap jadi JNI library (upstream ambrop72/badvpn tun2socks didesain
# jalan sebagai proses `main()` sendiri, bukan library dengan entry
# function seperti hev yang punya makro ENABLE_LIBRARY resmi) -- jadi di
# sini kita compile jadi EXECUTABLE ELF biasa, lalu disalin manual (lihat
# custom command di bawah) ke app/src/main/jniLibs/<ABI>/, supaya AGP ikut
# mem-bundle-nya ke dalam APK dan Kotlin (BadVpnTun2socksEngine) tinggal
# menjalankannya lewat ProcessBuilder saat runtime -- pola yang sama
# dipakai app-app tunnel Android lain untuk membundel core native yang
# didesain sebagai proses mandiri (v2ray-core, xray-core, dst).
#
# Source: fork "shadowsocks/badvpn" (BUKAN ambrop72/badvpn upstream asli).
# Fork ini menambahkan opsi CLI --tunfd/--tunmtu KHUSUS untuk Android --
# upstream asli cuma bisa buka /dev/net/tun sendiri lewat --tundev (butuh
# root, tidak dipakai VpnService), sedangkan Android WAJIB memakai fd yang
# SUDAH terbuka dari VpnService.Builder().establish(). Fork ini pula yang
# historisnya dipakai shadowsocks-android versi lama untuk kebutuhan
# persis sama.
#
# JUJUR (WAJIB DIBACA): blok CMake ini DITULIS dari pengetahuan umum
# tentang cara badvpn di-cross-compile untuk Android (mengikuti pola
# ExternalProject_Add hev-socks5-tunnel di atas -- badvpn sendiri juga
# pakai CMake sebagai build system upstream-nya, BUKAN autotools/Makefile
# polos), TAPI belum pernah dijalankan sungguhan (lingkungan penyusunan
# kode ini tidak punya NDK/CMake/network access untuk clone+compile+link
# beneran) -- SATU putaran CI user sudah menemukan & fix dua bug nyata
# dari sini (lihat CMAKE_POLICY_DEFAULT_CMP0057 di bawah), jadi anggap
# blok ini "pernah dites sekali, tapi belum tentu sampai APK jalan utuh".
# Kemungkinan hal LAIN yang masih perlu disesuaikan kalau CI berikutnya
# gagal lagi:
#   - Nama target CMake hasil compile tun2socks (di sini diasumsikan
#     "tun2socks", cek CMakeLists.txt badvpn sendiri kalau beda).
#   - Opsi CMAKE_ARGS yang badvpn butuhkan (BUILD_NOTHING_BY_DEFAULT=1
#     BUILD_TUN2SOCKS=1 adalah opsi upstream yang terdokumentasi, dipakai
#     apa adanya di sini).
#   - Apakah badvpn butuh dependency tambahan (mis. nspr/nss) untuk fitur
#     yang TIDAK dipakai di sini (--dns-resolver via mDNSResponder dst) --
#     kalau CMake badvpn gagal karena package itu tidak ada, opsi
#     BUILD_TUN2SOCKS sendirian SEHARUSNYA tidak butuh itu, tapi ini perlu
#     diverifikasi di build log sungguhan.
# WAJIB: build APK ini di mesin dengan Android SDK/NDK + koneksi internet,
# perhatikan baris log "badvpn_tun2socks_external" di output Gradle
# (externalNativeBuild), lalu tes koneksi VPN sungguhan di device fisik
# dengan ENGINE_BADVPN dipilih di kartu "VPN Setting" SEBELUM dipakai
# produksi -- kalau gagal, ENGINE_HEV (default) tetap tidak terpengaruh
# sama sekali oleh blok ini.
set(BADVPN_PREFIX ${CMAKE_CURRENT_BINARY_DIR}/badvpn-src)
set(BADVPN_SOURCE_DIR ${BADVPN_PREFIX}/badvpn)
set(BADVPN_BUILD_DIR ${BADVPN_PREFIX}/badvpn-build)
# Lokasi jniLibs SUMBER (bukan output build) app module -- disalin ke sini
# secara LANGSUNG (bukan lewat mekanisme auto-packaging CMake/AGP yang
# hanya mengenali target add_library) supaya AGP menganggapnya file
# jniLibs biasa seperti file .so lain yang memang sudah ada di folder ini,
# tanpa perlu utak-atik konfigurasi packaging tambahan.
set(BADVPN_JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

ExternalProject_Add(
    badvpn_tun2socks_external
    PREFIX ${BADVPN_PREFIX}
    SOURCE_DIR ${BADVPN_SOURCE_DIR}
    BINARY_DIR ${BADVPN_BUILD_DIR}
    GIT_REPOSITORY https://github.com/shadowsocks/badvpn.git
    GIT_TAG master
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_BUILD_TYPE=Release
        # FIX build error nyata (CI log user, 2025): badvpn punya
        # `cmake_minimum_required` SANGAT LAMA (peringatan build:
        # "Compatibility with CMake < 2.8.12 will be removed") -- karena itu,
        # policy CMP0057 ("if() supports IN_LIST") IKUT default ke OLD/unset
        # untuk seluruh proses configure badvpn ini (policy versi rendah
        # merambat ke SEMUA include berikutnya, termasuk toolchain file NDK
        # yang di-include lewat CMAKE_TOOLCHAIN_FILE di atas). android.toolchain
        # .cmake NDK modern (r27 di sini) MEMAKAI `if(... IN_LIST ...)` sendiri
        # (mis. cek ANDROID_SANITIZE) -- tanpa CMP0057=NEW, if() itu gagal
        # parse dengan persis error yang muncul di CI: "CMake Error ... if
        # given arguments: \"hwaddress\" \"IN_LIST\" \"ANDROID_SANITIZE\" ...
        # Unknown arguments specified". Baris ini MEMAKSA CMP0057=NEW lewat
        # variable CMAKE_POLICY_DEFAULT_* (cuma berlaku untuk policy yang
        # TIDAK di-set eksplisit oleh cmake_minimum_required badvpn sendiri --
        # CMP0057 diperkenalkan CMake 3.3, jauh di atas versi minimum badvpn,
        # jadi policy ini otomatis "unset" dan variable ini yang menentukan
        # default-nya). TIDAK memengaruhi hev-socks5-tunnel di atas sama
        # sekali (ExternalProject terpisah, CMAKE_ARGS ini cuma dikirim ke
        # configure step badvpn_tun2socks_external).
        -DCMAKE_POLICY_DEFAULT_CMP0057=NEW
        # FIX build error nyata (CI log user, putaran ke-3): "unknown target"
        # sudah teratasi (build tanpa --target), TAPI compile badvpn sendiri
        # gagal: system/BReactor_badvpn.h:39 "#error Unknown event backend
        # or too many event backends" & system/BUnixSignal.h:38 "#error
        # Unknown signal backend or too many signal backends". Dua header
        # itu isinya rantai #if/#elif yang MEMILIH implementasi reactor/
        # signal berdasarkan salah satu dari beberapa macro yang harus
        # SUDAH terdefinisi lewat -D (mis. BADVPN_USE_EPOLL, BADVPN_USE_SIGNALFD,
        # dst) -- normalnya macro ini disuntik OTOMATIS oleh CMakeLists.txt
        # badvpn sendiri lewat deteksi platform (try_compile/try_run), TAPI
        # baris command compiler di log CI (lihat -DBADVPN_BREACTOR_BADVPN
        # yang JELAS bukan nama macro valid manapun, bukan BADVPN_USE_EPOLL
        # dkk) menunjukkan deteksi itu GAGAL/salah saat CROSS-COMPILE ke
        # Android (try_run BIASANYA tidak bisa jalan sama sekali saat
        # cross-compiling tanpa emulator, karena binary hasil compile-nya
        # untuk arch Android, bukan bisa dieksekusi langsung di runner
        # x86_64 CI) -- akibatnya CMake badvpn jatuh ke semacam nilai
        # fallback yang tidak valid, bukan salah satu backend asli.
        #
        # FIX: paksa backend yang BENAR secara eksplisit lewat CMAKE_C_FLAGS
        # (ditambahkan, BUKAN mengganti -DBADVPN_BREACTOR_BADVPN yang sudah
        # ada -- itu tidak match apa pun jadi aman diabaikan/tidak
        # konflik). Android (bionic libc, minSdk 28 app ini) mendukung
        # epoll dan signalfd penuh, jadi BADVPN_USE_EPOLL + BADVPN_USE_SIGNALFD
        # adalah pasangan backend yang benar untuk Linux/Android modern.
        #
        # JUJUR: nama KEDUA macro ini saya tulis dari pengetahuan umum
        # source badvpn (system/BReactor_badvpn.h & system/BUnixSignal.h),
        # BUKAN hasil verifikasi langsung ke source-nya (tidak ada akses
        # network di lingkungan penyusunan kode ini) -- confidence CUKUP
        # TINGGI (ini nama macro yang cukup dikenal luas di project
        # badvpn), tapi BUKAN 100% pasti. Kalau CI berikutnya masih error
        # "#error Unknown ... backend" juga, buka LANGSUNG file
        # badvpn-src/badvpn/system/BReactor_badvpn.h dan
        # badvpn-src/badvpn/system/BUnixSignal.h di runner (folder itu
        # hasil clone, ada persis di path yang muncul di pesan error CMake
        # Error di log) -- daftar #elif defined(...) di situ adalah daftar
        # PASTI nama macro yang diterima, jadi tidak perlu tebak lagi.
        "-DCMAKE_C_FLAGS=-DBADVPN_USE_EPOLL -DBADVPN_USE_SIGNALFD"
        # Opsi upstream badvpn: matikan semua target default (udpgw, dst
        # -- app ini sudah punya UdpgwClient Kotlin sendiri, lihat
        # VpnSettingsStore.udpgwPort), nyalakan HANYA tun2socks.
        -DBUILD_NOTHING_BY_DEFAULT=1
        -DBUILD_TUN2SOCKS=1
    BUILD_COMMAND ${CMAKE_COMMAND} --build ${BADVPN_BUILD_DIR}
    # ^ FIX build error nyata (CI log user, putaran ke-2): configure SUKSES
    # (log CMake badvpn sendiri konfirmasi "tun2socks yes" di ringkasan
    # komponennya), tapi step build sebelumnya gagal "ninja: error: unknown
    # target 'tun2socks'" -- nama KOMPONEN di opsi -DBUILD_TUN2SOCKS=1
    # ternyata beda dari nama TARGET ninja/CMake sungguhan untuk
    # executable-nya (sempat ditebak "badvpn-tun2socks", TAPI tanpa akses
    # buat verifikasi ke source badvpn sungguhan, menebak nama lagi sama
    # berisikonya). DIGANTI ke pendekatan yang tidak butuh tebak nama sama
    # sekali: HAPUS --target, build target default ("all") saja -- karena
    # -DBUILD_NOTHING_BY_DEFAULT=1 di atas SUDAH membatasi apa saja yang
    # ke-configure ke target "all" (cuma tun2socks, komponen lain "no" di
    # ringkasan configure), jadi build tanpa --target seharusnya otomatis
    # HANYA compile tun2socks apa pun nama target CMake-nya sungguhan.
    # Byproduct di bawah (badvpn-tun2socks) tetap asumsi nama file OUTPUT
    # -- itu independen dari nama target CMake, dan kalaupun namanya
    # ternyata beda juga, errornya akan jelas: "byproduct file was not
    # created" dengan path lengkap yang gampang dicocokkan ke isi
    # sungguhan folder ${BADVPN_BUILD_DIR}/tun2socks/ di log CI.
    INSTALL_COMMAND ""
    LOG_BUILD ON
    LOG_OUTPUT_ON_FAILURE ON
    BUILD_BYPRODUCTS
        ${BADVPN_BUILD_DIR}/tun2socks/badvpn-tun2socks
)

# Salin binary hasil build + strip simbol debug (memperkecil ukuran APK,
# sama seperti STRIP=${CMAKE_STRIP} yang dipakai hev-socks5-tunnel di
# atas) LANGSUNG ke folder jniLibs sumber app module, dengan nama
# "lib...so" (lihat catatan BINARY_NAME di BadVpnTun2socksEngine.kt untuk
# alasan konvensi penamaan ini WAJIB, walau isinya executable bukan
# shared library).
add_custom_command(
    OUTPUT ${BADVPN_JNILIBS_DIR}/libbadvpn-tun2socks.so
    COMMAND ${CMAKE_COMMAND} -E make_directory ${BADVPN_JNILIBS_DIR}
    COMMAND ${CMAKE_STRIP} --strip-all
        ${BADVPN_BUILD_DIR}/tun2socks/badvpn-tun2socks
        -o ${BADVPN_JNILIBS_DIR}/libbadvpn-tun2socks.so
    DEPENDS badvpn_tun2socks_external
    COMMENT "Menyalin badvpn-tun2socks (${ANDROID_ABI}) ke jniLibs"
)
add_custom_target(badvpn_tun2socks_copy ALL
    DEPENDS ${BADVPN_JNILIBS_DIR}/libbadvpn-tun2socks.so
)
# tunneljni TIDAK butuh badvpn secara teknis (dua engine ini independen),
# tapi digantungkan di sini supaya "make"/ninja default (target ALL) ikut
# men-trigger badvpn_tun2socks_copy tanpa perlu invocation CMake terpisah.
add_dependencies(tunneljni badvpn_tun2socks_copy)
