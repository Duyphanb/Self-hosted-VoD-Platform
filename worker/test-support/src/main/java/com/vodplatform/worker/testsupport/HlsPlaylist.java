package com.vodplatform.worker.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal RFC 8216 media-playlist reader for test assertions. It is deliberately independent of the
 * worker's own output validator (Issue 5.5), so the two can check each other. The D27 MPEG-TS profile
 * has only segment URIs; tags that reference additional resources are outside that profile.
 */
public record HlsPlaylist(Integer targetDurationSeconds, String playlistType, boolean endList, List<Segment> segments) {
    private static final Pattern RESOURCE_URI_ATTRIBUTE = Pattern.compile("[:,]\\s*(?:[A-Z0-9-]+-)?URI\\s*=");

    public static HlsPlaylist parse(String text) {
        String[] lines = text.split("\r?\n", -1);
        if (!"#EXTM3U".equals(lines[0])) throw invalid("the first line must be #EXTM3U");
        Integer targetDuration = null;
        String playlistType = null;
        boolean endList = false;
        List<Segment> segments = new ArrayList<>();
        Double pendingDuration = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF:")) {
                if (pendingDuration != null) throw invalid("#EXTINF without a URI before line " + (i + 1));
                pendingDuration = extinfSeconds(line.substring("#EXTINF:".length()));
            } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                targetDuration = parseInt(line.substring("#EXT-X-TARGETDURATION:".length()));
            } else if (line.startsWith("#EXT-X-PLAYLIST-TYPE:")) {
                playlistType = line.substring("#EXT-X-PLAYLIST-TYPE:".length());
            } else if (line.equals("#EXT-X-ENDLIST")) {
                endList = true;
            } else if (line.startsWith("#EXT") && RESOURCE_URI_ATTRIBUTE.matcher(line).find()) {
                // Do not silently skip a key, init map, rendition or future URI-bearing extension.
                throw invalid("resource URI tag is outside the MPEG-TS output profile on line " + (i + 1));
            } else if (!line.startsWith("#")) {
                if (pendingDuration == null) throw invalid("URI without #EXTINF on line " + (i + 1));
                segments.add(new Segment(pendingDuration, line));
                pendingDuration = null;
            }
        }
        if (pendingDuration != null) throw invalid("the last #EXTINF has no URI");
        return new HlsPlaylist(targetDuration, playlistType, endList, List.copyOf(segments));
    }

    public double totalSeconds() {
        return segments.stream().mapToDouble(Segment::durationSeconds).sum();
    }

    private static double extinfSeconds(String value) {
        int comma = value.indexOf(',');
        try {
            double seconds = Double.parseDouble(comma < 0 ? value : value.substring(0, comma));
            if (!Double.isFinite(seconds) || seconds < 0) throw invalid("invalid #EXTINF duration " + value);
            return seconds;
        } catch (NumberFormatException e) {
            throw invalid("invalid #EXTINF duration " + value);
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalid("invalid integer " + value);
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid media playlist: " + reason);
    }

    public record Segment(double durationSeconds, String uri) {
    }
}
