package ch.so.agi.mcp.config;

import ch.so.agi.mcp.transport.SerializedStdioServerTransportProvider;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "spring.ai.mcp.server",
    name = "stdio",
    havingValue = "true")
public class StdioTransportConfig {

  @Bean
  @ConditionalOnMissingBean(McpServerTransportProviderBase.class)
  public McpServerTransportProviderBase serializedStdioServerTransport(
      @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
    return new SerializedStdioServerTransportProvider(new JacksonMcpJsonMapper(jsonMapper));
  }
}
