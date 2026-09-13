#include <jni.h>
#include <stddef.h>

/*
 * FITUR BARU (permintaan user, "pindahkan generate key ke native code"):
 * passphrase statis untuk ConfigCipher (lihat model/ConfigLock.kt) direkonstruksi
 * DI SINI (kode native, dikompilasi jadi libtunneljni.so), bukan lagi disimpan
 * sebagai byte array di Kotlin. Efeknya: orang yang cuma decompile file .dex
 * (mis. pakai jadx/apktool -- cara paling umum & paling gampang bongkar APK)
 * TIDAK akan menemukan potongan passphrase ini sama sekali, karena secara
 * fisik memang tidak ada di bytecode Java/Kotlin. Untuk sampai ke sini,
 * orang harus bongkar file .so (disassembly ARM/x86), yang jauh lebih
 * merepotkan daripada baca bytecode Dalvik.
 *
 * PENTING, batasan yang SAMA seperti catatan di ConfigLock.kt tetap berlaku:
 * ini menaikkan biaya analisis STATIS, bukan menghilangkannya. String masih
 * disimpan di sini sebagai byte array ter-XOR (bukan literal "SuryaVPN-..."
 * polos) supaya `strings`/`nm` terhadap file .so juga tidak langsung
 * ketemu -- tapi orang yang menjalankan app ini di debugger/Frida dengan
 * hook ke fungsi native tetap bisa dump nilai balik fungsi ini kapan saja.
 * Tidak ada cara di level native code menutup celah RUNTIME itu sepenuhnya.
 *
 * Nama fungsi JNI HARUS cocok persis dengan package + nama class + nama
 * method di sisi Kotlin (lihat ConfigCipher di model/ConfigLock.kt):
 *   package com.example.tunnelapp.model
 *   private object ConfigCipher { private external fun nativePassphrase(): String }
 *
 * Java_<package_pakai_underscore>_<NamaClass>_<namaMethod>
 */

/* "SuryaVPN-ConfigLock-v1" di-XOR byte demi byte dengan 0x5A -- angka
 * kuncinya SENGAJA sama dengan yang dulu dipakai di sisi Kotlin (lihat
 * riwayat ConfigLock.kt), cuma sekarang byte hasil XOR-nya dipindah &
 * direkonstruksi di sini. */
static const unsigned char kPassphraseXored[] = {
    0x09, 0x2F, 0x28, 0x23, 0x3B, 0x0C, 0x0A, 0x14, 0x77, 0x19, 0x35,
    0x34, 0x3C, 0x33, 0x3D, 0x16, 0x35, 0x39, 0x31, 0x77, 0x2C, 0x6B
};
static const unsigned char kXorKey = 0x5A;

JNIEXPORT jstring JNICALL
Java_com_example_tunnelapp_model_ConfigCipher_nativePassphrase(
    JNIEnv *env, jobject thiz)
{
    (void) thiz;

    size_t len = sizeof(kPassphraseXored);
    char plain[sizeof(kPassphraseXored) + 1];

    for (size_t i = 0; i < len; i++) {
        plain[i] = (char) (kPassphraseXored[i] ^ kXorKey);
    }
    plain[len] = '\0';

    jstring result = (*env)->NewStringUTF(env, plain);

    /* Bersihkan buffer plaintext dari stack sesegera mungkin setelah
     * dipakai -- jaring pengaman kecil terhadap sisa memori, bukan
     * proteksi kuat (JVM/JNI tetap pegang salinannya sendiri di jstring
     * yang dikembalikan). */
    for (size_t i = 0; i < len; i++) {
        plain[i] = 0;
    }

    return result;
}
