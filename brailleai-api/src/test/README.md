# brailleai-api tests

## Test type
- Unit tests only (fast, no Spring context).

## What is covered
- DTO behavior (`BrailleRequest`, `BrailleResponse`) and contract defaults (`BrailleTranslator`).
- Input resolution and response factory behavior.

## What is not covered
- Web layer behavior and translation engine integration.

## Run
```bash
mvn -f brailleai-api/pom.xml test
```
