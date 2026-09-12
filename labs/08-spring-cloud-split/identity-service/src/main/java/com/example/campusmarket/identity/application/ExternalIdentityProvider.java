package com.example.campusmarket.identity.application;

import java.net.URI;
import java.util.Map;

/** 外部 OIDC/CAS/SAML 适配器端口；本任务不启用外部提供方。 */
public interface ExternalIdentityProvider {
    URI authorizationUri(URI callback, String state);

    ExternalIdentity verifyCallback(Map<String, String> parameters);
}
