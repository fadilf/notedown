#include <jni.h>
#include <lame.h>
#include <cstdio>
#include <cstring>

// Test-only, per-call LAME state; reads the app's canonical 16 kHz mono PCM WAV.
static bool encode(const char* input, const char* output, int bitrate) {
    FILE* source = fopen(input, "rb");
    if (!source) return false;
    unsigned char header[44];
    if (fread(header, 1, sizeof(header), source) != sizeof(header) ||
        memcmp(header, "RIFF", 4) || memcmp(header + 8, "WAVEfmt ", 8) ||
        memcmp(header + 36, "data", 4) || header[22] != 1 || header[34] != 16 ||
        header[24] != 0x80 || header[25] != 0x3e) {
        fclose(source);
        return false;
    }
    FILE* target = fopen(output, "w+b");
    if (!target) { fclose(source); return false; }
    lame_t encoder = lame_init();
    bool ok = encoder != nullptr;
    if (ok) {
        lame_set_in_samplerate(encoder, 16000);
        lame_set_out_samplerate(encoder, 16000);
        lame_set_num_channels(encoder, 1);
        lame_set_mode(encoder, MONO);
        lame_set_VBR(encoder, vbr_off);
        lame_set_brate(encoder, bitrate);
        lame_set_quality(encoder, 5);
        lame_set_bWriteVbrTag(encoder, 1);
        ok = lame_init_params(encoder) >= 0;
        short pcm[8192];
        unsigned char compressed[16384];
        while (ok) {
            const auto count = fread(pcm, sizeof(short), 8192, source);
            if (!count) break;
            const int written = lame_encode_buffer(encoder, pcm, pcm, static_cast<int>(count), compressed, sizeof(compressed));
            ok = written >= 0 && fwrite(compressed, 1, written, target) == static_cast<size_t>(written);
        }
        if (ok) {
            const int written = lame_encode_flush(encoder, compressed, sizeof(compressed));
            ok = written >= 0 && fwrite(compressed, 1, written, target) == static_cast<size_t>(written);
            if (ok) lame_mp3_tags_fid(encoder, target);
        }
        lame_close(encoder);
    }
    ok = ok && !ferror(source) && !ferror(target);
    fclose(source);
    if (fclose(target)) ok = false;
    return ok;
}

extern "C" JNIEXPORT void JNICALL
Java_com_notedown_LabMp3Encoder_encode(JNIEnv* env, jobject, jstring input, jstring output, jint bitrate) {
    const char* source = env->GetStringUTFChars(input, nullptr);
    if (!source) return;
    const char* target = env->GetStringUTFChars(output, nullptr);
    if (!target) { env->ReleaseStringUTFChars(input, source); return; }
    const bool ok = encode(source, target, bitrate);
    env->ReleaseStringUTFChars(input, source);
    env->ReleaseStringUTFChars(output, target);
    if (!ok) env->ThrowNew(env->FindClass("java/io/IOException"), "MP3 encoding failed");
}
