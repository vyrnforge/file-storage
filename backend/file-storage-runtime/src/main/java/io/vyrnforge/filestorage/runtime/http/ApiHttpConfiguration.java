package io.vyrnforge.filestorage.runtime.http;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Explicit consumer composition; never imported by the health-only executable. */
@Configuration(proxyBeanMethods = false)
@Import({FileRoutes.class, HttpBoundaryConfiguration.class})
public class ApiHttpConfiguration {}
