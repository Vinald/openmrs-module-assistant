# Development Guide

Technical reference for building, configuring, and verifying the assistant module.
For a project overview, see [README.md](README.md).

## How It Works

The module has no API/service layer and no database changes. It registers a single servlet
filter, `WidgetInjectionFilter`, declaratively in `config.xml`, plus a no-op `ModuleActivator`
(required by OpenMRS's module loader).

The filter covers the legacy JSP UI, the uiframework/appui GSP pages (e.g. the Home dashboard),
and the React OWA in one place. It buffers each HTML response, checks the content type, and
inserts the widget's `<script>` tag before `</body>`. It skips static assets by file extension
and OWA routes (`/owa/*`), so it never buffers large JS, CSS, font, or pre-built asset responses.

## Project Structure

```
src/main/java/org/openmrs/module/assistant/
├── AssistantActivator.java          # No-op ModuleActivator (required by OpenMRS's module loader)
└── filter/
    └── WidgetInjectionFilter.java   # Buffers HTML responses and injects the widget <script> tag
src/main/resources/config.xml            # Module descriptor: id, version, activator, filter mapping
src/main/resources/assistant.properties  # Widget defaults, marker, static-asset suffixes
```

## Configuration

| Variable               | Default                 | Purpose |
|-------------------------|--------------------------|---------|
| `ASSISTANT_WIDGET_URL`  | `http://localhost:4000` | Base URL the injected `<script src>` / `data-service-url` point at. Set this in the distro's `.env` / `docker-compose.run.yml` to point at a deployed assistant widget instance. |

The `ASSISTANT_WIDGET_URL` default, the injected script's marker attribute, and the list of
static-asset file extensions the filter skips are read from `src/main/resources/assistant.properties`,
bundled into the module jar. Edit that file, not the filter class, to adjust any of them.

## Build

```bash
cd openmrs-module-assistant
mvn -q -B clean package
cp target/assistant-1.0.0.jar target/assistant-1.0.0.omod
```

Produces `target/assistant-1.0.0.omod`.

### One-time prerequisite: the `openmrs-api` dependency

`pom.xml` compiles against `org.openmrs.api:openmrs-api:2.4.6-cfl.3` (needed only for the
`ModuleActivator` interface), which isn't published to Maven Central or any reachable OpenMRS
Nexus repo. Bootstrap it once from a running distro container:

```bash
docker cp cfl-web-1:/usr/local/tomcat/webapps/openmrs/WEB-INF/lib/openmrs-api-2.4.6-cfl.3.jar /tmp/

cat > /tmp/openmrs-api-minimal.pom.xml <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.openmrs.api</groupId>
  <artifactId>openmrs-api</artifactId>
  <version>2.4.6-cfl.3</version>
  <packaging>jar</packaging>
</project>
EOF

mvn install:install-file -Dfile=/tmp/openmrs-api-2.4.6-cfl.3.jar \
  -DpomFile=/tmp/openmrs-api-minimal.pom.xml
```

Use `-DpomFile` (not `-Dfile` with groupId/artifactId/version flags): the jar's own embedded
`pom.xml` declares an unresolvable parent POM.

If the CFL distro upgrades its OpenMRS platform version, re-extract the jar from the new container
and update both this bootstrap step and the `<version>` in `pom.xml`.

## Test

```bash
mvn -q -B test
```

Runs the `WidgetInjectionFilter` unit test suite (`src/test/java`).

## Verify

```bash
curl -s -u admin:Admin123 "http://localhost/openmrs/ws/rest/v1/systemsetting?q=assistant&v=full"
```

`admin:Admin123` is OpenMRS's standard local/demo administrator account, valid only on a fresh
local distro instance. Look for `assistant.started = true`. Then load any CFL page in a browser
and confirm the floating chat button appears bottom-right.
