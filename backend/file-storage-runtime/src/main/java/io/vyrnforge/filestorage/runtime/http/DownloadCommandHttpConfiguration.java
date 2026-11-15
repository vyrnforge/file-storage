package io.vyrnforge.filestorage.runtime.http;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Explicit API/COMBINED opt-in, shared admission and neutral errors. */
@Configuration(proxyBeanMethods=false)
@Import({DownloadCommandRoutes.class,HttpBoundaryConfiguration.class})
public class DownloadCommandHttpConfiguration {}
