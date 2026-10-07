/* C-ABI wrapper around vgmstream's Bink Audio decoder (see vgmstream/COPYING), used by BodycamCraft to
 * decode the sounds in the player's own copy of Bodycam. */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "vgmstream/binka_dec.h"

static uint16_t u16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static int32_t s32(const uint8_t* p) { return (int32_t)(p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24)); }

/* Decodes a UE "ABEU" packet stream (everything after the header and its seek table, SEEK chunks included)
 * into interleaved 16-bit PCM. Returns the number of sample frames written, or a negative error. */
__declspec(dllexport) int32_t bcbinka_decode(const uint8_t* src, int32_t src_len, int32_t sample_rate,
                                              int32_t channels, int16_t* dst, int32_t max_frames) {
    binka_handle_t* h = binka_init(sample_rate, channels, BINKA_UEBA);
    if (!h) return -1;
    int frame_samples = binka_get_frame_samples(h);
    float* fbuf = calloc((size_t)frame_samples * channels, sizeof(float));
    if (!fbuf) { binka_free(h); return -2; }
    binka_reset(h);

    int32_t pos = 0, done = 0, err = 0;
    while (pos + 4 <= src_len && done < max_frames) {
        if (memcmp(src + pos, "SEEK", 4) == 0) {
            if (pos + 0x0f > src_len) break;
            int32_t entries = s32(src + pos + 0x0b);
            if (entries <= 0) { err = -3; break; }
            pos += 0x0f + entries * 2;
            continue;
        }
        if (u16(src + pos) != 0x9999) { err = -4; break; }
        int32_t size = u16(src + pos + 2), limit = 0;
        pos += 4;
        if (size == 0xFFFF) {
            if (pos + 4 > src_len) break;
            size = u16(src + pos);
            limit = u16(src + pos + 2);
            pos += 4;
        }
        if (pos + size > src_len) break;
        int n = binka_decode(h, (unsigned char*)(src + pos), size, fbuf);
        pos += size;
        if (n < 0) { err = -5; break; }
        if (limit && n > limit) n = limit;
        if (n > max_frames - done) n = max_frames - done;
        for (int i = 0; i < n * channels; i++) {
            float v = fbuf[i];
            if (v > 32767.0f) v = 32767.0f;
            if (v < -32768.0f) v = -32768.0f;
            dst[(size_t)done * channels + i] = (int16_t)v;
        }
        done += n;
    }
    free(fbuf);
    binka_free(h);
    return err && !done ? err : done;
}
