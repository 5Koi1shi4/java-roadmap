package com.example.campusmarket.identity.application;

import java.net.URI;
import java.util.Map;

/** Port for a future OIDC/CAS/SAML adapter; no external provider is enabled here. */
public interface ExternalIdentityProvider {
    URI authorizationUri(URI callback, String state);

    ExternalIdentity verifyCallback(Map<String, String> parameters);
}
