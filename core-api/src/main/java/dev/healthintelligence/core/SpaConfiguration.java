package dev.healthintelligence.core;

import java.io.IOException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the single-page app from {@code hip.spa.directory} (the Render profile, where one service
 * holds both, so the API and the session cookie share the origin as with nginx and the BFF).
 * Hashed assets are cached for a year; every other path that is not a file gets
 * {@code index.html}, so client-side routes survive a reload. API, sign-in and internal paths are
 * never answered with the page.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${hip.spa.directory:}'.isEmpty()")
class SpaConfiguration implements WebMvcConfigurer {

    private final String directory;

    SpaConfiguration(org.springframework.core.env.Environment environment) {
        String configured = environment.getRequiredProperty("hip.spa.directory");
        this.directory = configured.endsWith("/") ? configured : configured + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("file:" + directory + "assets/")
                .setCacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations("file:" + directory)
                .setCacheControl(CacheControl.noStore())
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        if (path.startsWith("api/") || path.startsWith("bff/") || path.startsWith("internal/")
                                || path.startsWith("actuator/") || path.equals("logout") || path.endsWith(".map")) {
                            return null;
                        }
                        Resource file = location.createRelative(path);
                        if (!path.isEmpty() && file.exists() && file.isReadable()) {
                            return file;
                        }
                        // A missing asset is a 404, not the page; anything else is a client route.
                        return path.startsWith("assets/") || path.contains(".") ? null
                                : new FileSystemResource(directory + "index.html");
                    }
                });
    }
}
