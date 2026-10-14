package com.sableos.provider;

/** Profile 声明的 provider 未在全局清单登记：显式报错点名，不降级、不换家、不留空。 */
public class ProviderNotFoundException extends RuntimeException {

  public ProviderNotFoundException(String providerName) {
    super("provider 未登记：" + providerName + "（请检查 sableos.providers 全局清单）");
  }
}
