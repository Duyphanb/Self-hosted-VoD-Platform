package com.vodplatform.worker.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HlsOutputCheckTests {
    @TempDir
    Path hls;

    @BeforeEach
    void writeValidOutput() throws IOException {
        Files.createDirectories(hls.resolve("segments"));
        for (String segment : new String[] {"seg_00000.ts", "seg_00001.ts", "seg_00002.ts"}) {
            Files.write(hls.resolve("segments").resolve(segment), new byte[] {0x47});
        }
        playlist(HlsPlaylistTests.FFMPEG_VOD);
    }

    @Test
    void acceptsCompleteOutputWithinOneSecondOfTheMediaDuration() throws IOException {
        assertThat(HlsOutputCheck.d27Violations(hls, 13.0)).isEmpty();
        assertThat(HlsOutputCheck.d27Violations(hls, 13.999)).isEmpty();
        assertThat(HlsOutputCheck.d27Violations(hls, 12.001)).isEmpty();
    }

    @Test
    void rejectsADurationMoreThanOneSecondOff() throws IOException {
        assertThat(HlsOutputCheck.d27Violations(hls, 14.01)).singleElement().asString().startsWith("EXTINF sum");
        assertThat(HlsOutputCheck.d27Violations(hls, 11.99)).singleElement().asString().startsWith("EXTINF sum");
    }

    @Test
    void rejectsMissingVodTypeOrEndList() throws IOException {
        playlist(HlsPlaylistTests.FFMPEG_VOD.replace("#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-PLAYLIST-TYPE:EVENT"));
        assertThat(HlsOutputCheck.d27Violations(hls, 13.0)).containsExactly("playlist type is not VOD");

        playlist(HlsPlaylistTests.FFMPEG_VOD.replace("#EXT-X-ENDLIST\n", ""));
        assertThat(HlsOutputCheck.d27Violations(hls, 13.0)).containsExactly("missing #EXT-X-ENDLIST");
    }

    @Test
    void rejectsAbsoluteEscapingOrNonTsSegmentUris() throws IOException {
        for (String uri : new String[] {"/segments/seg_00002.ts", "https://cdn.example/seg_00002.ts",
                "segments/../seg_00002.ts", "segments/seg_00002.mp4", "seg_00002.ts", "segments/sub/seg_00002.ts"}) {
            playlist(HlsPlaylistTests.FFMPEG_VOD.replace("segments/seg_00002.ts", uri));
            assertThat(HlsOutputCheck.d27Violations(hls, 13.0)).as(uri)
                    .contains("segment URI is not a relative segments/*.ts name: " + uri);
        }
    }

    @Test
    void rejectsMissingEmptyAndUnreferencedFiles() throws IOException {
        Files.delete(hls.resolve("segments/seg_00001.ts"));
        Files.write(hls.resolve("segments/seg_00002.ts"), new byte[0]);
        Files.writeString(hls.resolve("segments/seg_00003.ts"), "left over");

        assertThat(HlsOutputCheck.d27Violations(hls, 13.0)).containsExactlyInAnyOrder(
                "missing or empty segment: segments/seg_00001.ts",
                "missing or empty segment: segments/seg_00002.ts",
                "unreferenced file: " + Path.of("segments", "seg_00003.ts"));
    }

    @Test
    void rejectsAMissingOrEmptyPlaylist() throws IOException {
        playlist("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-ENDLIST\n");
        assertThat(HlsOutputCheck.d27Violations(hls, 0.5)).contains("no segments");

        Files.delete(hls.resolve(HlsOutputCheck.PLAYLIST));
        assertThat(HlsOutputCheck.d27Violations(hls, 13.0)).containsExactly("missing master.m3u8");
    }

    @Test
    void checksSegmentDurationsAgainstTheTarget() {
        HlsPlaylist valid = HlsPlaylist.parse(HlsPlaylistTests.FFMPEG_VOD);
        assertThat(HlsOutputCheck.segmentDurationViolations(valid, 6, 0.1)).isEmpty();

        HlsPlaylist shortMiddle = HlsPlaylist.parse(HlsPlaylistTests.FFMPEG_VOD.replaceFirst("#EXTINF:6.000000", "#EXTINF:4.000000"));
        assertThat(HlsOutputCheck.segmentDurationViolations(shortMiddle, 6, 0.1)).containsExactly("segment 0 lasts 4.000 s, target 6 s");

        HlsPlaylist tooLong = HlsPlaylist.parse(HlsPlaylistTests.FFMPEG_VOD.replace("#EXTINF:1.000000", "#EXTINF:7.000000"));
        assertThat(HlsOutputCheck.segmentDurationViolations(tooLong, 6, 0.1))
                .containsExactly("segment 2 lasts 7.000 s, target 6 s", "segment 2 exceeds #EXT-X-TARGETDURATION");

        HlsPlaylist noTarget = HlsPlaylist.parse(HlsPlaylistTests.FFMPEG_VOD.replace("#EXT-X-TARGETDURATION:6\n", ""));
        assertThat(HlsOutputCheck.segmentDurationViolations(noTarget, 6, 0.1)).containsExactly("missing #EXT-X-TARGETDURATION");
    }

    private void playlist(String text) throws IOException {
        Files.writeString(hls.resolve(HlsOutputCheck.PLAYLIST), text, StandardCharsets.UTF_8);
    }
}
