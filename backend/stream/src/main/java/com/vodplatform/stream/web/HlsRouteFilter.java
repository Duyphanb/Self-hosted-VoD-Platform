package com.vodplatform.stream.web;

import com.vodplatform.shared.web.CanonicalUuid;
import com.vodplatform.shared.web.RequestIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies HLS cache policy and correlation before security, including its 401 responses. */
public final class HlsRouteFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(HlsRouteFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        String requestId = RequestIds.resolve(request);
        try {
            chain.doFilter(request, response);
        } finally {
            if (response.getStatus() == 401 || response.getStatus() == 404) {
                log.info("HLS access denied status={} requestId={} assetId={}",
                        response.getStatus(), requestId, assetId(request));
            }
        }
    }

    private static UUID assetId(HttpServletRequest request) {
        String prefix = request.getContextPath() + "/hls/video-assets/";
        String path = request.getRequestURI();
        if (!path.startsWith(prefix)) return null;
        String text = path.substring(prefix.length()).split("/", 2)[0];
        try {
            return CanonicalUuid.parse(text);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
