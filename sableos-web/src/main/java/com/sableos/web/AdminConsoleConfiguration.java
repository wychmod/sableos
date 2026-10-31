package com.sableos.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

/**
 * 管理台（{@code /admin}）静态资源与 SPA 回落配置。
 *
 * <p>前端构建产物位于 {@code classpath:/static/admin/}，由 Vite 以 {@code base=/admin/} 生成：入口固定为 {@code
 * index.html}，其余资源文件名带内容指纹。这里只接管 {@code /admin/**}：
 *
 * <ul>
 *   <li>{@code /admin} 302 到 {@code /admin/}，保证前端 base 与浏览器地址一致；
 *   <li>{@code /admin/} 以及所有无扩展名的前端路由回落到 {@code index.html}，交给 History 路由；
 *   <li>指纹资源长期缓存，入口页禁止缓存，发布后立即生效。
 * </ul>
 *
 * <p>{@code /api/**}、{@code /swagger-ui/**} 等路径不受影响；带扩展名但不存在的资源直接 404，不会把缺失的 JS/CSS 伪装成页面返回。
 */
@Configuration
public class AdminConsoleConfiguration implements WebMvcConfigurer {

  private static final String ADMIN_ROOT = "classpath:/static/admin/";
  private static final String ADMIN_INDEX = "static/admin/index.html";
  private static final String EXTENSION_SEPARATOR = ".";

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/admin/assets/**")
        .addResourceLocations(ADMIN_ROOT + "assets/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic());

    registry
        .addResourceHandler("/admin/**")
        .addResourceLocations(ADMIN_ROOT)
        .setCacheControl(CacheControl.noStore())
        .resourceChain(false)
        .addResolver(new SpaFallbackResolver())
        .addResolver(new PathResourceResolver());
  }

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addRedirectViewController("/admin", "/admin/");
    registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
  }

  /** 无扩展名的请求视为前端路由，返回入口页；其余请求交给 {@link PathResourceResolver}。 */
  private static final class SpaFallbackResolver implements ResourceResolver {

    @Override
    public Resource resolveResource(
        HttpServletRequest request,
        String requestPath,
        List<? extends Resource> locations,
        ResourceResolverChain chain) {
      if (requestPath.contains(EXTENSION_SEPARATOR)) {
        return chain.resolveResource(request, requestPath, locations);
      }
      return new ClassPathResource(ADMIN_INDEX);
    }

    @Override
    public String resolveUrlPath(
        String resourcePath, List<? extends Resource> locations, ResourceResolverChain chain) {
      return chain.resolveUrlPath(resourcePath, locations);
    }
  }
}
