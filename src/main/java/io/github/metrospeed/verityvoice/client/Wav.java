package io.github.metrospeed.verityvoice.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Wraps raw 16-bit PCM in a WAV header so Verity's audio player can read it. */
final class Wav {
    static byte[] fromPcm(byte[] pcm, int sampleRate, int channels) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVEfmt ".getBytes())
                .putInt(16).putShort((short) 1).putShort((short) channels).putInt(sampleRate)
                .putInt(sampleRate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
                .put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }

    private Wav() {
    }
}
