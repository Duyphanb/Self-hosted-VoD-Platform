package com.vodplatform.worker.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class HlsPlaylistTests {
    static final String FFMPEG_VOD = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXTINF:6.000000,
            segments/seg_00000.ts
            #EXTINF:6.000000,
            segments/seg_00001.ts
            #EXTINF:1.000000,
            segments/seg_00002.ts
            #EXT-X-ENDLIST
            """;

    @Test
    void readsTheVodPlaylistFfmpegWrites() {
        HlsPlaylist playlist = HlsPlaylist.parse(FFMPEG_VOD);

        assertThat(playlist.targetDurationSeconds()).isEqualTo(6);
        assertThat(playlist.playlistType()).isEqualTo("VOD");
        assertThat(playlist.endList()).isTrue();
        assertThat(playlist.segments()).containsExactly(
                new HlsPlaylist.Segment(6.0, "segments/seg_00000.ts"),
                new HlsPlaylist.Segment(6.0, "segments/seg_00001.ts"),
                new HlsPlaylist.Segment(1.0, "segments/seg_00002.ts"));
        assertThat(playlist.totalSeconds()).isEqualTo(13.0);
    }

    @Test
    void acceptsCrlfTitlesAndUnknownTags() {
        HlsPlaylist playlist = HlsPlaylist.parse("#EXTM3U\r\n#EXT-X-FUTURE:1\r\n#EXTINF:3.5,title\r\na.ts\r\n");

        assertThat(playlist.segments()).containsExactly(new HlsPlaylist.Segment(3.5, "a.ts"));
        assertThat(playlist.endList()).isFalse();
        assertThat(playlist.playlistType()).isNull();
    }

    @Test
    void rejectsMalformedPlaylists() {
        for (String text : List.of("", "#EXT-X-ENDLIST\n", " #EXTM3U\n",
                "#EXTM3U\nsegments/a.ts\n",
                "#EXTM3U\n#EXTINF:6,\n#EXTINF:6,\na.ts\n",
                "#EXTM3U\n#EXTINF:6,\n",
                "#EXTM3U\n#EXTINF:abc,\na.ts\n",
                "#EXTM3U\n#EXTINF:-1,\na.ts\n",
                "#EXTM3U\n#EXTINF:NaN,\na.ts\n",
                "#EXTM3U\n#EXT-X-TARGETDURATION:six\n")) {
            assertThatThrownBy(() -> HlsPlaylist.parse(text)).as(text).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
