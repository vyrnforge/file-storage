package io.vyrnforge.filestorage.runtime.http;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Explicit administrative composition, independent of the consumer controller. */
@Configuration(proxyBeanMethods = false)
@Import({PolicyAdminRoutes.class, HttpBoundaryConfiguration.class})
public class PolicyAdminHttpConfiguration {}
