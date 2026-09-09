#include <jni.h>
#include <string.h>
#include "hev-main.h"

/*
 * Nama fungsi JNI HARUS cocok persis dengan package + nama class + nama
 * method di sisi Kotlin (lihat HevSocks5Bridge.kt):
 *   package com.example.tunnelapp.tunnel
 *   object HevSocks5Bridge { external fun startTunnel(...); external fun stopTunnel() }
 *
 * Java_<package_pakai_underscore>_<NamaClass>_<namaMethod>
 */

JNIEXPORT jint JNICALL
Java_com_example_tunnelapp_tunnel_HevSocks5Bridge_startTunnel(
    JNIEnv *env, jobject thiz, jstring configYaml, jint tunFd)
{
    (void) thiz;

    const char *configChars = (*env)->GetStringUTFChars(env, configYaml, NULL);
    if (configChars == NULL) {
        return -1;
    }

    unsigned int len = (unsigned int) strlen(configChars);

    /* Fungsi ini BLOCKING: baru return setelah hev_socks5_tunnel_quit()
     * dipanggil atau terjadi error. Wajib dipanggil dari thread terpisah
     * di sisi Kotlin, jangan dari main thread. */
    int result = hev_socks5_tunnel_main_from_str(
        (const unsigned char *) configChars, len, (int) tunFd);

    (*env)->ReleaseStringUTFChars(env, configYaml, configChars);

    return (jint) result;
}

JNIEXPORT void JNICALL
Java_com_example_tunnelapp_tunnel_HevSocks5Bridge_stopTunnel(
    JNIEnv *env, jobject thiz)
{
    (void) env;
    (void) thiz;
    hev_socks5_tunnel_quit();
}
