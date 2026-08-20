# openmrs-module-assistant

An OpenMRS module for the CFL distro that injects the assistant floating chat widget into
every HTML page the platform serves, using a single servlet filter.

## Features

- Injects the widget script tag into all dynamically rendered pages, instead of hand-editing
  each rendering stack.
- Skips static assets and OWA routes to avoid unnecessary response buffering.
- Idempotent: never injects the widget twice into the same response.
- Widget URL is configurable via the `ASSISTANT_WIDGET_URL` environment variable.
- No API layer and no database changes: a single stateless filter.

## Requirements

- Java 8
- Apache Maven
- OpenMRS platform 2.4.6, or the CFL fork thereof

## Quick Start

```bash
mvn -q -B clean package
cp target/assistant-1.0.0.jar target/assistant-1.0.0.omod
```

For implementation notes, the full build/bootstrap process, configuration options, and
verification steps, see [DEVELOPMENT.md](DEVELOPMENT.md).

## Author

Okiror Samuel Vinald

## License

Licensed under the [Mozilla Public License 2.0](LICENSE), the standard license used across
OpenMRS projects, and subject to the OpenMRS Healthcare Disclaimer included in that file.
