# brailleai-application tests

## Test type
- Unit tests and lightweight configuration tests (no full Spring context boot).

## What is covered
- Bean factory wiring in `ApplicationConfig`.
- CORS mapping rules in `WebCorsConfig`.

## What is not covered
- End-to-end startup and external engine connectivity.

## Run
```bash
mvn -f brailleai-application/pom.xml test
```
