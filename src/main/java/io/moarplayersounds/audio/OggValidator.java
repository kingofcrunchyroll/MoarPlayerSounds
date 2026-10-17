package io.moarplayersounds.audio;

/**
 * Pure-Java structural validation of Ogg Vorbis data. Never decodes audio, never touches the
 * filesystem, and only reads inside the supplied array, so malformed input can at worst be rejected.
 */
public final class OggValidator {
    public static final int MAX_BYTES = 256 * 1024;
    public static final long MAX_DURATION_MS = 5_000;
    public static final int MIN_RATE = 8_000, MAX_RATE = 48_000;
    public static final int MAX_PAGES = 2_048;

    public record Result(boolean ok, String error, int channels, int sampleRate, long durationMs) {
        static Result fail(String why) { return new Result(false, why, 0, 0, 0); }
    }

    private static final int[] CRC = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int k = 0; k < 8; k++) r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : (r << 1);
            CRC[i] = r;
        }
    }

    private OggValidator() {}

    public static Result validate(byte[] d) {
        if (d == null || d.length < 58) return Result.fail("file too small to be an Ogg Vorbis file");
        if (d.length > MAX_BYTES) return Result.fail("file too large (" + d.length / 1024 + " KiB, max " + MAX_BYTES / 1024 + " KiB)");

        int pos = 0, pages = 0, channels = 0, rate = 0;
        long serial = 0, lastGranule = -1;
        int expectedSeq = 0;
        boolean sawEos = false;

        while (pos < d.length) {
            if (sawEos) return Result.fail("data after end-of-stream page (chained streams are not supported)");
            if (++pages > MAX_PAGES) return Result.fail("too many Ogg pages");
            if (d.length - pos < 27) return Result.fail("truncated Ogg page header");
            if (d[pos] != 'O' || d[pos + 1] != 'g' || d[pos + 2] != 'g' || d[pos + 3] != 'S') return Result.fail("not an Ogg file (missing OggS capture pattern)");
            if (d[pos + 4] != 0) return Result.fail("unsupported Ogg version");
            int flags = d[pos + 5] & 0xFF;
            long granule = le64(d, pos + 6);
            long ser = le32(d, pos + 14) & 0xFFFFFFFFL;
            long seq = le32(d, pos + 18) & 0xFFFFFFFFL;
            int crc = le32(d, pos + 22);
            int nSeg = d[pos + 26] & 0xFF;
            if (d.length - pos < 27 + nSeg) return Result.fail("truncated Ogg segment table");
            int bodyLen = 0;
            for (int i = 0; i < nSeg; i++) bodyLen += d[pos + 27 + i] & 0xFF;
            int total = 27 + nSeg + bodyLen;
            if (d.length - pos < total) return Result.fail("truncated Ogg page body");

            if (pages == 1) {
                serial = ser;
                if ((flags & 0x02) == 0) return Result.fail("first page is not marked beginning-of-stream");
                if (bodyLen != 30) return Result.fail("first page is not a Vorbis identification header");
                int b = pos + 27 + nSeg;
                if ((d[b] & 0xFF) != 1 || d[b+1] != 'v' || d[b+2] != 'o' || d[b+3] != 'r' || d[b+4] != 'b' || d[b+5] != 'i' || d[b+6] != 's')
                    return Result.fail("not Ogg Vorbis (Opus/FLAC in Ogg are not supported)");
                if (le32(d, b + 7) != 0) return Result.fail("unsupported Vorbis version");
                channels = d[b + 11] & 0xFF;
                rate = le32(d, b + 12);
                if ((d[b + 29] & 1) == 0) return Result.fail("corrupt Vorbis identification header");
                if (channels != 1) return Result.fail(channels == 2
                        ? "stereo audio is not supported: positional sound needs mono (mix down to mono first)"
                        : "unsupported channel count " + channels + " (mono required)");
                if (rate < MIN_RATE || rate > MAX_RATE) return Result.fail("sample rate " + rate + " Hz outside " + MIN_RATE + "-" + MAX_RATE);
            } else {
                if (ser != serial) return Result.fail("multiple logical streams are not supported");
                if ((flags & 0x02) != 0) return Result.fail("unexpected second beginning-of-stream page");
            }
            if (seq != expectedSeq) return Result.fail("Ogg page sequence broken (file corrupt or truncated)");
            expectedSeq++;

            byte[] copy = new byte[total];
            System.arraycopy(d, pos, copy, 0, total);
            copy[22] = copy[23] = copy[24] = copy[25] = 0;
            if (crc(copy) != crc) return Result.fail("checksum mismatch on page " + pages + " (file corrupt)");

            if ((flags & 0x04) != 0) { sawEos = true; lastGranule = granule; }
            pos += total;
        }
        if (!sawEos) return Result.fail("missing end-of-stream page (file truncated)");
        if (lastGranule <= 0) return Result.fail("could not determine duration");
        long ms = lastGranule * 1000L / rate;
        if (ms > MAX_DURATION_MS) return Result.fail("clip too long (" + ms + " ms, max " + MAX_DURATION_MS + " ms)");
        if (ms < 20) return Result.fail("clip too short");
        return new Result(true, null, channels, rate, ms);
    }

    private static int crc(byte[] b) {
        int r = 0;
        for (byte x : b) r = (r << 8) ^ CRC[((r >>> 24) & 0xFF) ^ (x & 0xFF)];
        return r;
    }
    private static int le32(byte[] d, int o) {
        return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8 | (d[o + 2] & 0xFF) << 16 | (d[o + 3] & 0xFF) << 24;
    }
    private static long le64(byte[] d, int o) {
        return (le32(d, o) & 0xFFFFFFFFL) | ((long) le32(d, o + 4) << 32);
    }
}
