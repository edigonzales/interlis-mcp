package ch.so.agi.mcp;

import ch.so.agi.mcp.service.RuntimeIdentity;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
  public static void main(String[] args) {
    RuntimeIdentity.current();
    SpringApplication application = new SpringApplication(Application.class);
    application.setDefaultProperties(Map.of("interlis.mcp.stdio.shutdown-on-eof", "true"));
    application.run(args);
  }
}
