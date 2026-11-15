package io.vyrnforge.filestorage.runtime.http;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Explicit platform administration composition; not imported by the default executable. */
@Configuration(proxyBeanMethods = false)
@Import({BackendAdminRoutes.class, HttpBoundaryConfiguration.class})
public class BackendAdminHttpConfiguration {}
