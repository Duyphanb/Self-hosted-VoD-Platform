package com.vodplatform.playback.service;

import com.vodplatform.catalog.exception.EpisodeNotFoundException;
import com.vodplatform.catalog.service.EpisodeService;
import com.vodplatform.encoding.exception.VideoAssetNotFoundException;
import com.vodplatform.encoding.persistence.VideoAssetRepository;
import com.vodplatform.shared.web.RequestIds;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADR-010 D14/D22 and ADR-011 D4: two small reads, ending before any storage I/O. */
@Service
public class HlsAccessService {
    private static final Logger log = LoggerFactory.getLogger(HlsAccessService.class);
    private final VideoAssetRepository assets;
    private final EpisodeService episodes;
    private final String hlsBucket;

    public HlsAccessService(VideoAssetRepository assets, EpisodeService episodes,
                            @Value("${storage.hls-bucket}") String hlsBucket) {
        this.assets = assets;
        this.episodes = episodes;
        this.hlsBucket = hlsBucket;
    }

    @Transactional(readOnly = true)
    public HlsAccess requireAccess(UUID assetId) {
        var asset = assets.findCurrentReadyById(assetId).orElseThrow(VideoAssetNotFoundException::new);
        try {
            episodes.requireVisible(asset.getEpisodeId());
        } catch (EpisodeNotFoundException hidden) {
            throw new VideoAssetNotFoundException();
        }
        String key = asset.getHlsMasterObjectKey();
        String prefix = "video-assets/" + assetId + "/attempt-";
        if (!hlsBucket.equals(asset.getHlsBucket()) || key == null
                || !Pattern.matches(Pattern.quote(prefix) + "[1-9][0-9]*/master\\.m3u8", key)) {
            log.warn("HLS integrity failure assetId={} requestId={}", assetId, RequestIds.current().orElse(null));
            throw new VideoAssetNotFoundException();
        }
        return new HlsAccess(hlsBucket, key, key.substring(0, key.length() - "master.m3u8".length()));
    }
}
