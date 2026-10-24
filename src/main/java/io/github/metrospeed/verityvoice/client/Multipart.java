package io.github.metrospeed.verityvoice.client;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pulls the uploaded file out of the multipart/form-data request Verity sends for transcription. */
final class Multipart {
    private static final Pattern BOUNDARY = Pattern.compile("boundary=\"?([^\";]+)\"?");

    static byte[] filePart(byte[] body, String contentType) {
        if (contentType == null) return null;
        Matcher m = BOUNDARY.matcher(contentType);
        if (!m.find()) return null;
        byte[] delimiter = ("\r\n--" + m.group(1)).getBytes(StandardCharsets.ISO_8859_1);
        byte[] filename = "filename=".getBytes(StandardCharsets.ISO_8859_1);
        byte[] headerEnd = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

        int name = indexOf(body, filename, 0);
        if (name < 0) return null;
        int start = indexOf(body, headerEnd, name);
        if (start < 0) return null;
        start += headerEnd.length;
        int end = indexOf(body, delimiter, start);
        return Arrays.copyOfRange(body, start, end < 0 ? body.length : end);
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private Multipart() {
    }
}
