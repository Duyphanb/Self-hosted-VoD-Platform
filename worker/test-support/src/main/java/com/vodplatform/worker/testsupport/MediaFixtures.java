package com.vodplatform.worker.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generates {@link MediaFixture} files on demand with FFmpeg's lavfi test sources, so tests never need
 * committed or third-party media. Clips are short and low resolution to keep CI fast.
 */
public final class MediaFixtures {
    public static final double SOURCE_SECONDS = 13;
    /** The length of the Issue 5.5 encode-matrix sources, from {@code H264_AAC_1080P30_MP4} on. */
    public static final double SHORT_SOURCE_SECONDS = 7;
    private static final String VIDEO = "testsrc2=size=640x360:rate=30:duration=13";
    private static final String PORTRAIT_VIDEO = "testsrc2=size=360x640:rate=30:duration=13";
    private static final String TONE = "sine=frequency=440:sample_rate=48000:duration=13";
    private static final String SHORT_TONE = "sine=frequency=440:sample_rate=48000:duration=7";
    private static final Set<MediaFixture> SHORT_SOURCES = EnumSet.of(MediaFixture.H264_AAC_1080P30_MP4,
            MediaFixture.PORTRAIT_720X1280_MP4, MediaFixture.PORTRAIT_1080X1920_MP4, MediaFixture.ODD_641X359_WEBM,
            MediaFixture.SD_853X480_MP4, MediaFixture.FPS_60_MP4, MediaFixture.FPS_120_MP4, MediaFixture.FPS_29_97_MP4,
            MediaFixture.SCENE_CUTS_MP4);
    private static final List<String> FAST_H264 = List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p");
    private static final List<String> STEREO_AAC = List.of("-c:a", "aac", "-ac", "2");
    private final MediaTools tools;
    private final Path directory;
    private final Map<MediaFixture, Path> created = new EnumMap<>(MediaFixture.class);

    public MediaFixtures(MediaTools tools, Path directory) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.directory = directory.toAbsolutePath();
        try {
            Files.createDirectories(this.directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns the fixture, generating it (and any source it is derived from) on first use. */
    public synchronized Path path(MediaFixture fixture) {
        Path existing = created.get(fixture);
        if (existing != null) return existing;
        Path target = directory.resolve(fixture.fileName());
        try {
            create(fixture, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot generate " + fixture, e);
        }
        created.put(fixture, target);
        return target;
    }

    /** The length a valid fixture was generated with: {@link #SOURCE_SECONDS} or {@link #SHORT_SOURCE_SECONDS}. */
    public static double sourceSeconds(MediaFixture fixture) {
        if (!fixture.valid()) throw new IllegalArgumentException(fixture + " is not a valid source");
        return SHORT_SOURCES.contains(fixture) ? SHORT_SOURCE_SECONDS : SOURCE_SECONDS;
    }

    /** A path the playlist fixtures reference; it is never created, so opening it can only fail. */
    public Path canary() {
        return directory.resolve("canary-never-created.ts");
    }

    private void create(MediaFixture fixture, Path target) throws IOException {
        switch (fixture) {
            // veryfast gives the High profile cameras write; ultrafast sources below are Constrained Baseline.
            case H264_AAC_MP4 -> encode(target, inputs(VIDEO, TONE),
                    List.of("-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p"), STEREO_AAC,
                    List.of("-movflags", "+faststart", "-shortest"));
            case VP9_OPUS_WEBM -> encode(target, inputs(VIDEO, TONE),
                    List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "400k"),
                    List.of("-c:a", "libopus", "-ac", "2", "-shortest"));
            case H264_HIGH10_FLAC_MKV -> encode(target, inputs(VIDEO, TONE),
                    List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le", "-profile:v", "high10"),
                    List.of("-c:a", "flac", "-ac", "2", "-shortest"));
            case PORTRAIT_H264_AAC_MP4 -> encode(target, inputs(PORTRAIT_VIDEO, TONE), FAST_H264, STEREO_AAC,
                    List.of("-movflags", "+faststart", "-shortest"));
            case SILENT_H264_MP4 -> encode(target, inputs(VIDEO), FAST_H264, List.of("-movflags", "+faststart"));
            case HEVC_MAIN10_HDR_AC3_MKV -> encode(target, inputs(VIDEO, TONE),
                    // PQ/BT.2020 tags on both the frames and the stream, as an HDR10 camera file carries them.
                    List.of("-vf", "setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc",
                            "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=error",
                            "-pix_fmt", "yuv420p10le", "-profile:v", "main10",
                            "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc"),
                    List.of("-c:a", "ac3", "-ac", "6", "-b:a", "384k", "-shortest"));
            case ROTATED_90_MP4 -> rotate(target, 90);
            case ROTATED_MINUS_90_MKV -> rotate(target, -90);
            case ROTATED_180_MP4 -> rotate(target, 180);
            case ROTATED_45_MP4 -> rotate(target, 45);
            case ROTATE_TAG_90_MKV -> encode(target, List.of("-i", "file:" + path(MediaFixture.H264_AAC_MP4)),
                    List.of("-map", "0", "-c", "copy", "-metadata:s:v:0", "rotate=90"));
            // The muxer writes the reflection to the Display Matrix; the coded pixels are copied unchanged.
            case VERTICAL_REFLECTION_MP4 -> encode(target,
                    List.of("-display_vflip:v:0", "-i", "file:" + path(MediaFixture.H264_AAC_MP4)),
                    List.of("-map", "0", "-c", "copy"));
            case H264_AAC_1080P30_MP4 -> encode(target, inputs(shortVideo("1920x1080", "30"), SHORT_TONE),
                    List.of("-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p"), STEREO_AAC,
                    List.of("-movflags", "+faststart", "-shortest"));
            case PORTRAIT_720X1280_MP4 -> encode(target, inputs(shortVideo("720x1280", "30"), SHORT_TONE), FAST_H264,
                    STEREO_AAC, List.of("-shortest"));
            case PORTRAIT_1080X1920_MP4 -> encode(target, inputs(shortVideo("1080x1920", "30"), SHORT_TONE), FAST_H264,
                    STEREO_AAC, List.of("-shortest"));
            // testsrc2 and crop round a 4:2:0 frame to even sizes, so the odd crop happens in 4:4:4.
            case ODD_641X359_WEBM -> encode(target, inputs(shortVideo("642x360", "30") + ",format=yuv444p,crop=641:359:0:0",
                    SHORT_TONE), List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "400k",
                    "-pix_fmt", "yuv420p"), List.of("-c:a", "libopus", "-ac", "2", "-shortest"));
            case SD_853X480_MP4 -> encode(target, inputs(shortVideo("854x480", "30") + ",format=yuv444p,crop=853:480:0:0",
                    SHORT_TONE), List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv444p"), STEREO_AAC,
                    List.of("-shortest"));
            case FPS_60_MP4 -> encode(target, inputs(shortVideo("640x360", "60"), SHORT_TONE), FAST_H264, STEREO_AAC,
                    List.of("-shortest"));
            case FPS_120_MP4 -> encode(target, inputs(shortVideo("640x360", "120"), SHORT_TONE), FAST_H264, STEREO_AAC,
                    List.of("-shortest"));
            case FPS_29_97_MP4 -> encode(target, inputs(shortVideo("640x360", "30000/1001"), SHORT_TONE), FAST_H264,
                    STEREO_AAC, List.of("-shortest"));
            // Four unrelated pictures cut together at 2.5, 4.0 and 5.7 s, none on a 6 s segment boundary.
            case SCENE_CUTS_MP4 -> encode(target, List.of("-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=2.5",
                    "-f", "lavfi", "-i", "smptebars=size=640x360:rate=30:duration=1.5",
                    "-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30,trim=duration=1.7",
                    "-f", "lavfi", "-i", "rgbtestsrc=size=640x360:rate=30:duration=1.3", "-f", "lavfi", "-i", SHORT_TONE),
                    List.of("-filter_complex", "[0:v][1:v][2:v][3:v]concat=n=4:v=1:a=0,format=yuv420p[v]",
                            "-map", "[v]", "-map", "4:a"), FAST_H264, STEREO_AAC, List.of("-shortest"));
            case EBML_UNDER_MP4_KEY -> Files.copy(path(MediaFixture.VP9_OPUS_WEBM), target);
            case TEXT_UNDER_MP4_KEY -> Files.writeString(target, "Plain text uploaded under a video key.\n",
                    StandardCharsets.UTF_8);
            case HLS_PLAYLIST_UNDER_MP4_KEY -> Files.writeString(target, "#EXTM3U\n#EXT-X-TARGETDURATION:6\n"
                    + "#EXTINF:6,\nfile:" + canary() + "\n#EXT-X-ENDLIST\n", StandardCharsets.UTF_8);
            case FFCONCAT_UNDER_MKV_KEY -> Files.writeString(target, "ffconcat version 1.0\nfile '" + canary() + "'\n",
                    StandardCharsets.UTF_8);
            case TRUNCATED_MOOV_AT_END_MP4 -> truncate(moovAtEndSource(), target, 50);
            case TRUNCATED_FASTSTART_MP4 -> truncate(path(MediaFixture.H264_AAC_MP4), target, 30);
            case AUDIO_ONLY_MP4 -> encode(target, inputs(TONE), STEREO_AAC);
            case COVER_ART_ONLY_MP4 -> encode(target, List.of("-f", "lavfi", "-i", TONE, "-i", "file:" + cover()),
                    List.of("-map", "0:a", "-map", "1:v", "-c:v", "png", "-disposition:v:0", "attached_pic"), STEREO_AAC);
            case VIDEO_WITH_COVER_ART_MP4 -> encode(target, List.of("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=10:duration=2",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2", "-i", "file:" + cover()),
                    List.of("-map", "0:v", "-map", "1:a", "-map", "2:v",
                            "-c:v:0", "libx264", "-preset", "ultrafast", "-pix_fmt:v:0", "yuv420p",
                            "-c:v:1", "png", "-disposition:v:1", "attached_pic", "-c:a", "aac"));
            case MPEG2_VIDEO_MKV -> encode(target, inputs("testsrc2=size=640x360:rate=25:duration=3",
                    "sine=frequency=440:sample_rate=48000:duration=3"),
                    List.of("-c:v", "mpeg2video", "-c:a", "aac", "-shortest"));
            // An allowed video beside an audio stream whose decoder (mp2) is outside the allowlist.
            case MP2_AUDIO_MKV -> encode(target, inputs("testsrc2=size=320x180:rate=10:duration=2",
                    "sine=frequency=440:sample_rate=48000:duration=2"), FAST_H264, List.of("-c:a", "mp2", "-shortest"));
            case NINE_STREAMS_MKV, NINE_STREAMS_MP4 -> encode(target, inputs("testsrc2=size=320x180:rate=10:duration=2",
                    "sine=frequency=440:sample_rate=48000:duration=2"), nineStreamMaps(), FAST_H264, List.of("-c:a", "aac"));
            case OVER_CAP_2560X1440_MP4 -> encode(target, inputs("testsrc2=size=2560x1440:rate=5:duration=1"), FAST_H264);
        }
    }

    /** Copies H264_AAC_MP4 with a Display Matrix; -display_rotation is an input option, so nothing is re-encoded. */
    private void rotate(Path target, int degrees) {
        encode(target, List.of("-display_rotation:v:0", String.valueOf(degrees), "-i", "file:" + path(MediaFixture.H264_AAC_MP4)),
                List.of("-map", "0", "-c", "copy"));
    }

    private static String shortVideo(String size, String rate) {
        return "testsrc2=size=" + size + ":rate=" + rate + ":duration=" + (int) SHORT_SOURCE_SECONDS;
    }

    private Path moovAtEndSource() throws IOException {
        Path source = directory.resolve("source-moov-at-end.mp4");
        if (!Files.exists(source)) encode(source, inputs(VIDEO, TONE), FAST_H264, STEREO_AAC, List.of("-shortest"));
        return source;
    }

    private Path cover() throws IOException {
        Path cover = directory.resolve("source-cover.png");
        if (!Files.exists(cover)) encode(cover, inputs("color=c=red:s=320x320:d=1"), List.of("-frames:v", "1"));
        return cover;
    }

    private static List<String> nineStreamMaps() {
        List<String> maps = new ArrayList<>(List.of("-map", "0:v"));
        for (int i = 0; i < 8; i++) maps.addAll(List.of("-map", "1:a"));
        return maps;
    }

    private static List<String> inputs(String... lavfiSources) {
        List<String> arguments = new ArrayList<>();
        for (String source : lavfiSources) arguments.addAll(List.of("-f", "lavfi", "-i", source));
        return arguments;
    }

    @SafeVarargs
    private void encode(Path target, List<String> inputs, List<String>... outputOptions) {
        List<String> arguments = new ArrayList<>(List.of("-nostdin", "-hide_banner", "-loglevel", "error", "-y"));
        arguments.addAll(inputs);
        for (List<String> options : outputOptions) arguments.addAll(options);
        arguments.add(target.toString());
        MediaTools.Result result = tools.ffmpeg(arguments);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("ffmpeg could not generate " + target.getFileName() + ": " + result.stderr());
        }
    }

    private static void truncate(Path source, Path target, int percent) throws IOException {
        long keep = Files.size(source) * percent / 100;
        try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(target)) {
            out.write(in.readNBytes(Math.toIntExact(keep)));
        }
    }
}
