package dev.healthintelligence.core.shared;

import java.net.InetAddress;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Network origin of the current request for audit and consent records. Behind a load balancer the
 * remote address is the proxy's until forwarded headers are trusted (Phase 7).
 */
@Component
public class RequestOrigin {

    static final int MAX_USER_AGENT_LENGTH = 512;

    public HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    public InetAddress ipAddress(HttpServletRequest request) {
        try {
            return InetAddress.ofLiteral(request.getRemoteAddr());
        } catch (IllegalArgumentException | NullPointerException notAnAddress) {
            return null;
        }
    }

    public String userAgent(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        return userAgent.length() <= MAX_USER_AGENT_LENGTH ? userAgent : userAgent.substring(0, MAX_USER_AGENT_LENGTH);
    }
}
