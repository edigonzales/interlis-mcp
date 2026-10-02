package ch.so.agi.mcp;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/** Imports the INTERLIS capabilities without starting an application or transport. */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = "ch.so.agi.mcp", excludeFilters = {
    @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootApplication.class),
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = "ch\\.so\\.agi\\.mcp\\.config\\.StdioTransportConfig")
})
public class InterlisMcpModuleConfiguration {}
