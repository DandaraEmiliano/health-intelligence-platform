package dev.healthintelligence.core.shared;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Correlation id shown in error responses and stored in audit events: the trace id when a trace
 * is active (so support can jump to the trace), otherwise a random id kept for the request.
 */
@Component
public class CorrelationIds {

    private static final String ATTRIBUTE = CorrelationIds.class.getName();

    private final ObjectProvider<Tracer> tracer;

    CorrelationIds(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    public String current() {
        Tracer activeTracer = tracer.getIfAvailable();
        Span span = activeTracer == null ? null : activeTracer.currentSpan();
        if (span != null) {
            return span.context().traceId();
        }
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return random();
        }
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof String id) {
            return id;
        }
        String id = random();
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    private static String random() {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
