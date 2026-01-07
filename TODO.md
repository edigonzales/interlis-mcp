# interlis-mcp


## todo
- list all units? time? math und text functions?
- list all geometry types
- metaattribute!! ili2db json etc.
- diff vs komplett vs. inline -> siehe chatgpt
- in agents.md: surface vs area (überlappen...)
- Wie unterbindet man, dass er "NUMERIC" als Attributtyp mitschickt? Wie kann man die Attributtypen grundsätzlich robuster gestalten?
- sanitize name 
- sanitize reservierte Wörter
- write tests
- document: json (codex) inkl. beispiel
- document: diagram mcp (codex)
- kann man "capabilities" anschauen? json?
- 

- partial compilation?


## showcase

- Claude GUI
- VSCode mit eigenem Agent und Systemprompts o.ä.
- theia statt vscode?
 
## Docker

Build the container image locally:

```bash
docker build -t interlis-mcp .
```

Run the MCP server with standard input/output connected to your host:

```bash
docker run --rm -i interlis-mcp
```

Because the MCP transport is STDIO-based you should not allocate a TTY (no `-t`) and must keep standard input open (the `-i` flag). For Docker Compose set `stdin_open: true` and `tty: false` on the service so that tools can exchange JSON-RPC messages with the server without any extra escape sequences.
