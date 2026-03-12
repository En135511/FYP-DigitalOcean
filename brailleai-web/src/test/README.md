# brailleai-web tests

## Test type
- Unit tests at controller/handler level (direct class invocation, no full Spring boot).

## What is covered
- Input validation and direction handling in `BrailleController`.
- Download response behavior for valid and invalid requests.
- HTTP error mapping in `GlobalExceptionHandler`.

## What is not covered
- Full HTTP integration with Spring MVC wiring.

## Run
```bash
mvn -f brailleai-web/pom.xml test
```
