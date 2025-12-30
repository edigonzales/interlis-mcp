package ch.so.agi.mcp.model;

import java.util.Map;

public class AttributeLineResponse {
  private String iliSnippet;
  private Map<String,Integer> cursorHint;

  public AttributeLineResponse() {}

  public AttributeLineResponse(String iliSnippet) {
    this.iliSnippet = iliSnippet;
    this.cursorHint = Map.of("line", 0, "col", 0);
  }

  public String getIliSnippet() { return iliSnippet; }
  public void setIliSnippet(String iliSnippet) { this.iliSnippet = iliSnippet; }

  public Map<String,Integer> getCursorHint() { return cursorHint; }
  public void setCursorHint(Map<String,Integer> cursorHint) { this.cursorHint = cursorHint; }
}
