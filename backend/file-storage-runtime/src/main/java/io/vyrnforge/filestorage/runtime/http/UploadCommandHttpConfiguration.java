package io.vyrnforge.filestorage.runtime.http;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Explicit opt-in, shares existing API role and admission guards. */
@Configuration(proxyBeanMethods=false)
@Import({UploadCommandRoutes.class,HttpBoundaryConfiguration.class})
public class UploadCommandHttpConfiguration {}
