# openmrs-module-cflassist

An OpenMRS module for the [CFL distro](../openmrs-distro-cfl) that injects the
[`cfl-assist`](../cfl-assist) floating chat widget script tag into every HTML page the distro
serves — the legacy JSP UI, the uiframework/appui GSP pages (e.g. the Home dashboard), and the
React OWA — via a single servlet filter (`WidgetInjectionFilter`), instead of hand-editing each
rendering stack separately.

The module has no API/service layer and no database changes: it's a filter registered
declaratively in `config.xml`, plus a no-op `ModuleActivator` (OpenMRS requires one to be
present). See `src/main/java/org/openmrs/module/cflassist/filter/WidgetInjectionFilter.java` for
the implementation notes — it buffers each response, checks for `text/html`, and inserts the
widget's `<script>` tag before `</body>`, skipping static assets by file extension so it doesn't
buffer large JS/CSS/font files (that was a real bug: buffering everything exhausted Tomcat's
worker thread pool under real browser load).

## Features

- Injects the `cfl-assist` widget `<script>` tag into every dynamically-rendered HTML page
  (legacy JSP UI and uiframework/appui GSP pages) with a single servlet filter, instead of
  hand-editing each rendering stack.
- Skips static assets (JS, CSS, images, fonts, etc., matched by file extension) and OWA routes
  (`/owa/*`) so it never buffers large response bodies — avoids the Tomcat worker-thread
  exhaustion that full-response buffering caused under real browser load.
- Idempotent: checks for a `data-cflassist-widget` marker before inserting, so the script tag is
  never injected twice into the same response.
- Widget URL is configurable via the `CFLASSIST_WIDGET_URL` environment variable, falling back to
  `http://localhost:4000` for local development.
- No API/service layer, no database schema changes, no persisted data — the module is a single
  stateless filter.

## Requirements

- Java 8 (the build uses `maven.compiler.release=8` to match the CFL distro's runtime JVM)
- Apache Maven
- OpenMRS platform `2.4.6` (or the CFL fork thereof) — declared via `require_version` in
  `config.xml`
- Docker, only if deploying into a local [CFL distro](../openmrs-distro-cfl) instance as described
  below

## Configuration

| Variable               | Default                 | Purpose                                             |
|-------------------------|--------------------------|------------------------------------------------------|
| `CFLASSIST_WIDGET_URL`  | `http://localhost:4000` | Base URL the injected `<script src>`/`data-service-url` point at. Set this in the distro's `.env` / `docker-compose.run.yml` to point at a deployed `cfl-assist` widget instance. |

## Project Structure

```
src/main/java/org/openmrs/module/cflassist/
├── CflAssistActivator.java          # No-op ModuleActivator (required by OpenMRS's module loader)
└── filter/
    └── WidgetInjectionFilter.java   # Buffers HTML responses and injects the widget <script> tag
src/main/resources/config.xml        # Module descriptor: id, version, activator, filter mapping
```

## Build

```bash
cd openmrs-module-cflassist
mvn -q -B clean package
cp target/cflassist-1.0.0.jar target/cflassist-1.0.0.omod
```

Produces `target/cflassist-1.0.0.omod`.

### One-time prerequisite: the `openmrs-api` dependency

`pom.xml` compiles against `org.openmrs.api:openmrs-api:2.4.6-cfl.3` (needed only for the
`ModuleActivator` interface). This exact CFL-forked version isn't published to Maven Central or
any reachable OpenMRS Nexus repo, so a fresh machine needs to bootstrap it once from the running
distro container:

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

Use `-DpomFile` explicitly (not just `-Dfile` with groupId/artifactId/version flags) — otherwise
Maven extracts the jar's own embedded `pom.xml`, which declares a parent POM
(`org.openmrs:openmrs:pom:2.4.6-cfl.3`) that isn't resolvable anywhere either, and the build fails
trying to fetch it. The minimal standalone pom above sidesteps that.

If the CFL distro upgrades its OpenMRS platform version, re-extract the jar from the new
container and update both this bootstrap step and the `<version>` in `pom.xml`.

## Deploy into the distro

```bash
cp target/cflassist-1.0.0.omod ../openmrs-distro-cfl/cfl/web/cfl-modules/cflassist-1.0.0.omod
cd ../openmrs-distro-cfl/cfl
docker-compose -f docker-compose.build.yml -f docker-compose.run.yml \
  -f docker-compose.debug.yml -f docker-compose.db.yml up --build -d
```

This rebuilds the `openmrscorecfl` image (baking the new omod into `/opt/cfl-modules`) and
recreates the `cfl-web-1` container. Check `.env`'s `INITIAL_STARTUP` is `false` first, unless you
actually intend a full re-initialization (fresh DB, ~30min+ OCL concept import) — see the distro's
own README.

**First deploy of a new module version needs a manual start**: OpenMRS persists
`cflassist.started` as a global property and uses it to decide whether to auto-start the module on
boot. If a build ever fails to start (check `Administration > Manage Modules` for a
`[Not Started]` module or an "error starting the module" alert), fix the issue, redeploy, then
manually click Start once from that page — after that it auto-starts on every future boot.

## Verify

```bash
curl -s -u admin:Admin123 "http://localhost/openmrs/ws/rest/v1/systemsetting?q=cflassist&v=full"
```

Look for `cflassist.started = true`. Then load any CFL page in a browser and confirm the floating
chat button appears bottom-right.

## Author

Okiror Samuel Vinald

## License

Licensed under the [Mozilla Public License 2.0](LICENSE), the standard license used across
OpenMRS core and module repositories. Per [OpenMRS's licensing convention](http://openmrs.org/license/),
distribution is also subject to the OpenMRS Healthcare Disclaimer reproduced in the `LICENSE` file:
OpenMRS is a global collaborative project not warranted or represented to be suitable or fit for
any particular purpose, including direct patient care, and implementers/users are solely
responsible for testing and validating the software before relying on it.

This module depends at compile time on `org.openmrs.api:openmrs-api`, which is itself part of the
OpenMRS platform and licensed under the same terms; see the [OpenMRS](https://openmrs.org) project
for details.
