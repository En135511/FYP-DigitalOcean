# brailleai-liblouis tests

## Test type
- Unit tests with temporary filesystem fixtures.

## What is covered
- Liblouis table discovery and safe table resolution (`LiblouisTableRegistry`).
- CLI translator preconditions and text normalization behavior (`LiblouisCliTranslator`).

## What is not covered
- Real native/CLI translation execution against installed Liblouis binaries.

## Run
```bash
mvn -f brailleai-liblouis/pom.xml test
```
