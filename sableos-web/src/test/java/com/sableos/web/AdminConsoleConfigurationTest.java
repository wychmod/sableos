package com.sableos.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 管理台托管：入口跳转、SPA 回落、缺失资源 404。 */
@SpringJUnitWebConfig(AdminConsoleConfigurationTest.WebConfig.class)
class AdminConsoleConfigurationTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  @DisplayName("管理台根路径_重定向到带斜杠的入口")
  void adminRootRedirectsToSlash() throws Exception {
    mvc.perform(get("/admin"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/"));
  }

  @Test
  @DisplayName("管理台入口_转发到index")
  void adminEntryForwardsToIndex() throws Exception {
    mvc.perform(get("/admin/"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/admin/index.html"));
  }

  @Test
  @DisplayName("前端路由_回落到入口页并声明HTML")
  void spaRouteFallsBackToIndex() throws Exception {
    mvc.perform(get("/admin/sessions"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/html"))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("<div id=\"app\"></div>")));
  }

  @Test
  @DisplayName("缺失的指纹资源_返回404而不是入口页")
  void missingAssetReturnsNotFound() throws Exception {
    mvc.perform(get("/admin/assets/missing-0000.js")).andExpect(status().isNotFound());
  }

  @Configuration
  @EnableWebMvc
  @Import(AdminConsoleConfiguration.class)
  static class WebConfig {}
}
